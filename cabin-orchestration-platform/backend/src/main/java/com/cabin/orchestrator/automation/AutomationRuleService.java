package com.cabin.orchestrator.automation;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceProfile;
import com.cabin.orchestrator.presence.PresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Java-side simple rules evaluator — runs in parallel to Node-RED flows.
 * Node-RED handles complex / visual rules; this handles critical safety rules
 * that must work even if Node-RED is down.
 *
 * Found 2026-08-11 (user report, comparing the real product against
 * impressive.llc's marketing site): this class existed and looked complete
 * -- real thresholds, a real doc header describing a rule hierarchy -- but
 * was never actually wired up. Nothing anywhere called evaluate(), and
 * triggerAlert()/triggerWarn()/escalateAlarm() were TODO-stubs that only
 * logged. The Rules & Alerts sidebar's BuiltinRules component was already
 * claiming these rules "active: true" with a green dot -- a real, safety-
 * relevant gap between what the product claimed and what it did, not just a
 * missing feature. See EventConsumer's pollLoop() for the other half of the
 * fix (evaluate() is now actually called for every persisted event) and
 * docs/ontology.yaml's automation_alert_see_think_act entity for the full
 * writeup.
 *
 * Rule evaluation order:
 * 1. Safety / life-safety (smoke, CO, freeze)  → immediate MQTT alarm + email
 * 2. Utility alerts (pressure, power)           → MQTT warn + email
 * 3. Security events (motion, lock)             → MQTT notify; severity depends on PresenceProfile
 * 4. Informational telemetry                    → no action
 *
 * Presence-aware rules (PresenceProfile context):
 *   Lock unlocked while AT_CABIN or AWAY  → ALERT (no one should be entering)
 *   Lock unlocked while AT_HOME           → OK (expected)
 *   Lock unlocked while BOTH_OCCUPIED     → WARN (flag for awareness, not alarm)
 *
 *   Water pressure low while the cabin is unoccupied (AWAY or AT_HOME, i.e.
 *   nobody has a live presence signal AT_CABIN/BOTH_OCCUPIED) → CRITICAL,
 *   "unexpected use" framing, real notification. While the cabin IS occupied
 *   → WARN only (someone running a fixture is the ordinary explanation,
 *   matches this class's own existing "Utility alerts → warn" tier). This is
 *   the current, honest approximation of "no routine explains it" --
 *   presence-absence, not an actual schedule/routine system, which doesn't
 *   exist in this codebase yet. Don't read the UI copy as claiming more than
 *   that.
 */
@Service
public class AutomationRuleService {

    private static final Logger log = LoggerFactory.getLogger(AutomationRuleService.class);

    @Value("${cabin.devices.waterPressure.lowPsiAlert:30}")
    private double lowPsiAlert;

    @Value("${cabin.devices.waterPressure.highPsiAlert:75}")
    private double highPsiAlert;

    @Value("${cabin.devices.thermostat.freezeRiskTempF:38}")
    private double freezeRiskTempF;

    private final PresenceService presenceService;
    private final EventPublisher eventPublisher;

    public AutomationRuleService(PresenceService presenceService, EventPublisher eventPublisher) {
        this.presenceService = presenceService;
        this.eventPublisher = eventPublisher;
    }

    public void evaluate(CabinEvent event) {
        switch (event.eventType()) {
            case "TELEMETRY"       -> evaluateTelemetry(event);
            case "ALARM"           -> escalateAlarm(event);
            case "MOTION_DETECTED" -> handleMotion(event);
            case "STATE_CHANGE"    -> evaluateStateChange(event);
            default -> log.debug("No rule for event type: {}", event.eventType());
        }
    }

    private void evaluateTelemetry(CabinEvent event) {
        Object psiRaw = event.payload().get("psi");
        if (psiRaw instanceof Number psi) {
            double val = psi.doubleValue();
            if (val < lowPsiAlert)  evaluateWaterPressureLow(event.sourceDeviceId(), val);
            if (val > highPsiAlert) triggerWarn(event.sourceDeviceId(), "WATER_PRESSURE_HIGH", "PSI: " + val, Map.of("psi", val));
        }

        Object tempRaw = event.payload().get("temp_f");
        if (tempRaw instanceof Number temp && temp.doubleValue() < freezeRiskTempF) {
            triggerAlert(event.sourceDeviceId(), "FREEZE_RISK",
                "Freeze risk", "Temp reads " + temp + "°F, below the " + freezeRiskTempF + "°F threshold.",
                "Notify Nate", Map.of("temp_f", temp), List.of(String.valueOf(temp) + "°F"));
        }
    }

    /**
     * The flagship See/Think/Act scenario: cabin unoccupied + pressure below
     * threshold = unexpected use, real CRITICAL alert. Cabin occupied = an
     * ordinary explanation exists (someone's running a fixture), stays WARN.
     */
    private void evaluateWaterPressureLow(String deviceId, double psi) {
        PresenceProfile profile = presenceService.get();
        boolean cabinOccupied = profile == PresenceProfile.AT_CABIN || profile == PresenceProfile.BOTH_OCCUPIED;

        String see = "Pressure dropped below the safe range.";
        List<String> tags = List.of("CABIN - " + profile.label().toUpperCase(), psi + " PSI",
            cabinOccupied ? "EXPECTED USE" : "UNEXPECTED USE");

        if (cabinOccupied) {
            String context = "The cabin is " + profile.label().toLowerCase()
                + ", so a fixture running is a reasonable explanation. Mechanical room sensor reports " + psi + " PSI.";
            triggerWarn(deviceId, "WATER_PRESSURE_LOW", see + " " + context, Map.of("psi", psi, "presenceProfile", profile.name()));
            return;
        }

        String context = "The cabin is " + profile.label().toLowerCase()
            + ", no fixture is expected to be running, and the mechanical room sensor reports " + psi + " PSI.";
        triggerAlert(deviceId, "WATER_PRESSURE_LOW", see, context, "Alert Nate",
            Map.of("psi", psi, "presenceProfile", profile.name()), tags);
    }

    private void escalateAlarm(CabinEvent event) {
        log.error("SAFETY ALARM from {}: {}", event.sourceDeviceId(), event.payload());
        triggerAlert(event.sourceDeviceId(), "SAFETY_ALARM",
            "Safety alarm triggered.", "Device reports an active alarm condition: " + event.payload(),
            "Alert Nate immediately", event.payload(), List.of("LIFE SAFETY"));
    }

    private void handleMotion(CabinEvent event) {
        PresenceProfile profile = presenceService.get();
        log.info("Motion detected: {} (profile: {})", event.sourceDeviceId(), profile.label());
        // At AWAY: escalate motion to WARN; otherwise informational
        if (profile == PresenceProfile.AWAY || profile == PresenceProfile.AT_CABIN) {
            triggerWarn(event.sourceDeviceId(), "MOTION_WHILE_UNOCCUPIED",
                "Motion at home while profile is " + profile.label(), Map.of("presenceProfile", profile.name()));
        }
    }

    /**
     * Evaluates STATE_CHANGE events against the active presence profile.
     * Payload expects: { "deviceType": "LOCK", "state": "unlocked" }
     */
    private void evaluateStateChange(CabinEvent event) {
        Object typeRaw  = event.payload().get("deviceType");
        Object stateRaw = event.payload().get("state");
        if (!(typeRaw instanceof String deviceType) || !(stateRaw instanceof String state)) return;

        if ("LOCK".equals(deviceType)) {
            evaluateLockState(event.sourceDeviceId(), state);
        }
    }

    private void evaluateLockState(String deviceId, String state) {
        boolean unlocked = "unlocked".equalsIgnoreCase(state) || "open".equalsIgnoreCase(state);
        if (!unlocked) return;

        PresenceProfile profile = presenceService.get();
        switch (profile) {
            case AT_HOME ->
                log.debug("Lock {} unlocked — AT_HOME, no alert", deviceId);
            case BOTH_OCCUPIED ->
                triggerWarn(deviceId, "LOCK_UNLOCKED_BOTH_OCCUPIED",
                    "Lock unlocked while both locations are occupied — verify expected", Map.of("presenceProfile", profile.name()));
            case AT_CABIN, AWAY ->
                triggerAlert(deviceId, "LOCK_UNLOCKED_WHILE_AWAY",
                    "Lock unlocked.", "No one should be home (profile: " + profile.label() + ").",
                    "Alert Nate", Map.of("presenceProfile", profile.name()), List.of(profile.label()));
        }
    }

    /** CRITICAL-tier alert: published as a real CabinEvent, structured for the See/Think/Act card UI. */
    private void triggerAlert(String deviceId, String ruleId, String see, String context, String act,
                               Map<String, Object> extra, List<String> tags) {
        log.warn("ALERT — {} on {}: {}", ruleId, deviceId, context);
        publish(deviceId, ruleId, "CRITICAL", see, context, act, extra, tags);
    }

    /** WARN-tier: persisted and visible in event history, but NOT pushed (NtfyAlertPublisher only pushes CRITICAL). */
    private void triggerWarn(String deviceId, String warnType, String detail, Map<String, Object> extra) {
        log.warn("WARN — {} on {}: {}", warnType, deviceId, detail);
        publish(deviceId, warnType, "WARN", detail, detail, "Logged, no push (see docs/ontology.yaml)", extra, List.of());
    }

    private void publish(String deviceId, String ruleId, String severity, String see, String context,
                          String act, Map<String, Object> extra, List<String> tags) {
        Map<String, Object> payload = new LinkedHashMap<>(extra);
        payload.put("ruleId", ruleId);
        payload.put("see", see);
        payload.put("think", context);
        payload.put("act", act);
        payload.put("tags", tags);
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), deviceId, "AUTOMATION_ALERT", severity, Instant.now(), payload));
    }
}
