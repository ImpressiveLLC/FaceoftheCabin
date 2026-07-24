package com.cabin.orchestrator.automation;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.presence.PresenceProfile;
import com.cabin.orchestrator.presence.PresenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Java-side simple rules evaluator — runs in parallel to Node-RED flows.
 * Node-RED handles complex / visual rules; this handles critical safety rules
 * that must work even if Node-RED is down.
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

    public AutomationRuleService(PresenceService presenceService) {
        this.presenceService = presenceService;
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
            if (val < lowPsiAlert)  triggerAlert(event.sourceDeviceId(), "WATER_PRESSURE_LOW",  "PSI: " + val);
            if (val > highPsiAlert) triggerAlert(event.sourceDeviceId(), "WATER_PRESSURE_HIGH", "PSI: " + val);
        }

        Object tempRaw = event.payload().get("temp_f");
        if (tempRaw instanceof Number temp && temp.doubleValue() < freezeRiskTempF) {
            triggerAlert(event.sourceDeviceId(), "FREEZE_RISK", "Temp: " + temp + "°F");
        }
    }

    private void escalateAlarm(CabinEvent event) {
        log.error("SAFETY ALARM from {}: {}", event.sourceDeviceId(), event.payload());
        // TODO: publish to cabin/event/CRITICAL, send email/SMS via notification service
    }

    private void handleMotion(CabinEvent event) {
        PresenceProfile profile = presenceService.get();
        log.info("Motion detected: {} (profile: {})", event.sourceDeviceId(), profile.label());
        // At AWAY: escalate motion to WARN; otherwise informational
        if (profile == PresenceProfile.AWAY || profile == PresenceProfile.AT_CABIN) {
            triggerAlert(event.sourceDeviceId(), "MOTION_WHILE_UNOCCUPIED",
                "Motion at home while profile is " + profile.label());
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
                    "Lock unlocked while both locations are occupied — verify expected");
            case AT_CABIN, AWAY ->
                triggerAlert(deviceId, "LOCK_UNLOCKED_WHILE_AWAY",
                    "Lock is unlocked; no one should be home (profile: " + profile.label() + ")");
        }
    }

    private void triggerAlert(String deviceId, String alertType, String detail) {
        log.warn("ALERT — {} on {}: {}", alertType, deviceId, detail);
        // TODO: publish to cabin/event/ALERT and notification service
    }

    private void triggerWarn(String deviceId, String warnType, String detail) {
        log.warn("WARN — {} on {}: {}", warnType, deviceId, detail);
        // TODO: publish to cabin/event/WARN and notification service
    }
}
