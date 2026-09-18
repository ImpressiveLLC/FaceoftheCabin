package com.cabin.orchestrator.alerts;

import java.time.Instant;

/**
 * A person's explicit decision to stop being shown a specific alert for a
 * while -- IGNORED (no expiry, cleared only by an explicit un-ignore) or
 * SNOOZED (returns to "new" automatically once snoozedUntil passes,
 * regardless of whether the underlying condition recurred in the
 * meantime -- matches the user's own stated spec: a chosen duration, not
 * a "tell me only if it gets worse" heuristic this codebase has no
 * reliable way to implement for a historical automation-alert event).
 *
 * alertKey is a single unified key covering both alert kinds this app has
 * (see docs/ontology.yaml's ui_status_check_item): "device:{deviceId}:
 * {condition}" for an active_alert_condition (already ActiveAlert's own
 * alertId, reused verbatim -- no new derivation needed), or
 * "automation:{ruleId}:{sourceDeviceId}" for an automation_alert_
 * see_think_act event (derived client-side, since these come from a
 * generic event-log query, not a dedicated service the way device
 * conditions do). One acknowledgment table, one shape, regardless of
 * which kind it suppresses -- matches the "one unified snooze concept"
 * decision (not a different mechanism per kind).
 */
public record AlertAcknowledgment(
    String alertKey,
    String mode,            // IGNORED | SNOOZED
    Instant snoozedUntil,   // null for IGNORED; required for SNOOZED
    String acknowledgedBy,
    Instant acknowledgedAt
) {}
