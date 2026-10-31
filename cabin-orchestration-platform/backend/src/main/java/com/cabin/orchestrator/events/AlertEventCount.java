package com.cabin.orchestrator.events;

import java.time.Instant;

/** How many automation-alert events one (ruleId, device) pair produced in a window. */
public record AlertEventCount(String ruleId, String deviceId, long count, Instant firstAt, Instant lastAt) {}
