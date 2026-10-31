package com.cabin.orchestrator.alerts;

import java.time.Instant;

/**
 * One line of the alert history a person can review. Two sources feed it:
 * alert_history rows (device-condition RAISED/RESOLVED, and every
 * ignore/snooze/un-ignore/snooze-expiry) and automation events already in
 * cabin_event (always action RAISED, actor null). entryId is "h:{id}" or
 * "e:{eventId}" so the two never collide.
 */
public record AlertHistoryEntry(
    String entryId,
    Instant occurredAt,
    String alertKey,
    String kind,            // device | automation | unknown
    String deviceId,
    String condition,       // MISSED_CHECKIN | DEVICE_ALARM for device kind
    String ruleId,          // automation kind only
    String severity,
    String action,          // RAISED | RESOLVED | IGNORED | SNOOZED | UN_IGNORED | SNOOZE_EXPIRED
    String actor,           // email, or null for system-observed entries
    Instant snoozedUntil,
    String title
) {

    /** An ignore / snooze / un-ignore / snooze-expiry; kind, device and condition are parsed from the key. */
    public static AlertHistoryEntry ofAcknowledgment(String alertKey, String action, String actor, Instant snoozedUntil, Instant occurredAt) {
        AlertKeys.Parsed p = AlertKeys.parse(alertKey);
        return new AlertHistoryEntry(null, occurredAt, alertKey, p.kind(), p.deviceId(), p.condition(), p.ruleId(),
            null, action, actor, snoozedUntil, null);
    }
}
