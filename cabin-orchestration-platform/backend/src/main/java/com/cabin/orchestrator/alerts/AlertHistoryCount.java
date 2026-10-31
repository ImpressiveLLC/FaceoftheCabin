package com.cabin.orchestrator.alerts;

import java.time.Instant;

/** alert_history rows of one (alertKey, action) pair within a window -- input to the noisy-alert detector. */
public record AlertHistoryCount(String alertKey, String kind, String deviceId, String condition, String action,
                                long count, Instant firstAt, Instant lastAt) {}
