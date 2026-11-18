package com.cabin.orchestrator.alerts;

import com.cabin.orchestrator.events.CabinEventService;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Server-owned suppression floor; clients cannot assert acknowledgment severity. */
@Service
public class AlertAcknowledgmentPolicy {
    public static final Duration CRITICAL_MAX_SNOOZE = Duration.ofHours(1);
    private final ActiveAlertService activeAlerts;
    private final CabinEventService events;

    public AlertAcknowledgmentPolicy(ActiveAlertService activeAlerts, CabinEventService events) {
        this.activeAlerts = activeAlerts;
        this.events = events;
    }

    public Map<String, String> currentSeverities(Instant now) {
        Map<String, String> result = new HashMap<>();
        events.statusCheckSeverities(now.minus(Duration.ofHours(24))).forEach((key, severity) ->
            result.put(key, normalizedSeverity(severity)));
        for (ActiveAlert alert : activeAlerts.snapshot().alerts()) {
            result.merge(alert.alertId(), normalizedSeverity(alert.severity()), AlertAcknowledgmentPolicy::highestSeverity);
        }
        return result;
    }

    public String rejection(String severity, String mode, Instant until, Instant now) {
        if ("SNOOZED".equals(mode) && (until == null || !until.isAfter(now))) {
            return "snoozedUntil must be in the future";
        }
        if (!critical(severity)) return null;
        if (!"SNOOZED".equals(mode)) return "CRITICAL alerts can only be snoozed";
        if (until.isAfter(now.plus(CRITICAL_MAX_SNOOZE))) return "CRITICAL snoozes cannot exceed one hour";
        return null;
    }

    public boolean effective(AlertAcknowledgment ack, String severity, Instant now) {
        if ("SNOOZED".equals(ack.mode()) &&
            (ack.snoozedUntil() == null || !ack.snoozedUntil().isAfter(now))) return false;
        if (!critical(severity)) return true;
        // Unknown/legacy rows never prove that a person acknowledged this critical severity.
        return "SNOOZED".equals(ack.mode()) && critical(ack.acknowledgedSeverity())
            && ack.acknowledgedAt() != null && !ack.acknowledgedAt().isAfter(now)
            && ack.snoozedUntil().isAfter(ack.acknowledgedAt())
            && !ack.snoozedUntil().isAfter(ack.acknowledgedAt().plus(CRITICAL_MAX_SNOOZE));
    }

    private static String normalizedSeverity(String severity) {
        if ("WARN".equalsIgnoreCase(severity)) return "WARN";
        if ("INFO".equalsIgnoreCase(severity)) return "INFO";
        return "CRITICAL";
    }

    private static String highestSeverity(String a, String b) {
        if (critical(a) || critical(b)) return "CRITICAL";
        return "WARN".equalsIgnoreCase(a) || "WARN".equalsIgnoreCase(b) ? "WARN" : b;
    }

    private static boolean critical(String severity) { return "CRITICAL".equalsIgnoreCase(severity); }
}
