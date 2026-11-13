package com.cabin.orchestrator.weather;

import java.time.Instant;

/**
 * One UTC day of outdoor readings. {@code day} is the UTC midnight that starts the day,
 * the same bucket TelemetryDailyPoint uses in the production container (JVM default
 * zone is UTC there), so an outdoor point lines up with an indoor point on the chart.
 * Averages are plain means of the observations, not time-weighted. The daily low and high of
 * each reading ride along so the chart can show three single points per day (high, low,
 * mean) instead of drawing a line through values that were pulled from a weather service.
 * Each is null when no observation that day carried that reading.
 */
public record OutdoorDailyPoint(Instant day, String station, Double tempC, Double dewpointC,
                                Double rhPct, long sampleCount, boolean partial,
                                Double tempMinC, Double tempMaxC,
                                Double dewpointMinC, Double dewpointMaxC,
                                Double rhMinPct, Double rhMaxPct) {}
