package com.cabin.orchestrator.opportunities;

import java.time.Instant;
import java.util.Map;

/**
 * One instance of a detected optimization opportunity for one device.
 * detectedAt is set once, when the row is first created, and never
 * touched again by a later refresh of the same ongoing condition --
 * evidence carries whatever is current as of the most recent scan
 * (e.g. continuousHours), so "detected" always answers "when did this
 * first become worth a look," not "when did the job last confirm it."
 */
public record OptimizationOpportunity(
    String id,
    OpportunityType opportunityType,
    String deviceId,
    Instant detectedAt,
    Map<String, Object> evidence,
    OpportunityStatus status,
    Instant resolvedAt
) {}
