package com.cabin.orchestrator.alerts;

import java.time.Instant;

/**
 * A condition that is true now, derived from current device state.
 *
 * This is deliberately not a historical CabinEvent. A recent CRITICAL event
 * can remain in event history after its condition clears; active alerts must
 * disappear when the current device/check-in evidence no longer supports them.
 */
public record ActiveAlert(
    String alertId,
    String sourceDeviceId,
    String sourceName,
    String location,
    String condition,
    String severity,
    Instant evidenceAt,
    String title,
    String detail
) {}
