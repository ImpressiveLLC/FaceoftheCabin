package com.cabin.orchestrator.automation;

import com.cabin.orchestrator.events.CabinEvent;
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
 * 3. Security events (motion, lock)             → MQTT notify
 * 4. Informational telemetry                    → no action
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

    public void evaluate(CabinEvent event) {
        switch (event.eventType()) {
            case "TELEMETRY" -> evaluateTelemetry(event);
            case "ALARM"     -> escalateAlarm(event);
            case "MOTION_DETECTED" -> handleMotion(event);
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
        log.info("Motion detected: {}", event.sourceDeviceId());
        // TODO: check quiet hours from Google Calendar before alerting
    }

    private void triggerAlert(String deviceId, String alertType, String detail) {
        log.warn("ALERT — {} on {}: {}", alertType, deviceId, detail);
        // TODO: publish to cabin/event/WARN and notification service
    }
}
