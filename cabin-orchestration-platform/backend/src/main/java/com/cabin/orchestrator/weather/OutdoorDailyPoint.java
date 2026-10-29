package com.cabin.orchestrator.weather;

import java.time.Instant;

/**
 * One UTC day of outdoor readings. {@code day} is the UTC midnight that starts the day,
 * the same bucket TelemetryDailyPoint uses in the production container (JVM default
 * zone is UTC there), so an outdoor point lines up with an indoor point on the chart.
 * Averages are plain means of the observations, not time-weighted.
 */
public record OutdoorDailyPoint(Instant day, String station, Double tempC, Double dewpointC,
                                Double rhPct, long sampleCount, boolean partial) {}
