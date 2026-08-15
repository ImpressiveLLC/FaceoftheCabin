package com.cabin.orchestrator.alerts;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Point-in-time response for GET /api/alerts/active. */
public record ActiveAlertsSnapshot(
    Instant generatedAt,
    List<ActiveAlert> alerts,
    Map<String, Long> counts
) {}
