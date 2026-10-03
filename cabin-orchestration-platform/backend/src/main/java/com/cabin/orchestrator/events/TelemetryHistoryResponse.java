package com.cabin.orchestrator.events;

import java.util.List;

/**
 * W-21 (History range contract). Wraps {@code dailyAggregates()}'s points
 * with the range the server actually honored, so a caller whose requested
 * {@code days} exceeded {@code cabin.history.max-days} (or, for a demo
 * token, {@code cabin.demo.max-history-days} -- see DemoAccessFilter) can
 * tell the difference between "you got what you asked for" and "you got
 * less, here's how much," instead of silently rendering a shorter chart
 * with no explanation. {@code effectiveDays} is always the actual number
 * of days the query covered; {@code clamped} is true whenever that is
 * less than {@code requestedDays}.
 */
public record TelemetryHistoryResponse(int requestedDays, int effectiveDays, boolean clamped,
                                        List<TelemetryDailyPoint> points) {}
