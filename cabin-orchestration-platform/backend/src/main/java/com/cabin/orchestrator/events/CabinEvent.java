package com.cabin.orchestrator.events;

import java.time.Instant;
import java.util.Map;

public record CabinEvent(
    String eventId,
    String sourceDeviceId,
    String eventType,
    String severity,
    Instant timestamp,
    Map<String, Object> payload
) {}
