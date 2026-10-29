package com.cabin.orchestrator.weather;

import java.util.List;

/**
 * Wrapper, not a bare array, for the same reason TelemetryHistoryResponse is: a caller
 * must be able to tell a clamped range from a full one, and which station supplied the
 * numbers. {@code stations} is empty when no station is configured for the location.
 */
public record OutdoorHistoryResponse(String location, List<String> stations, int requestedDays,
                                     int effectiveDays, boolean clamped, List<OutdoorDailyPoint> points) {}
