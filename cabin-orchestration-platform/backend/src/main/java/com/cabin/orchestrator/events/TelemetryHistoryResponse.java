package com.cabin.orchestrator.events;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 *
 * W-173: an intraday answer also names its {@code bucket} ("5m", "15m", "1h") and the {@code timezone} its
 * bucket boundaries are aligned to. A day answer carries neither, so it is unchanged (the day boundary is
 * still the database session's, backlog W-30).
 */
public record TelemetryHistoryResponse(int requestedDays, int effectiveDays, boolean clamped,
                                        List<TelemetryDailyPoint> points,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) String bucket,
                                        @JsonInclude(JsonInclude.Include.NON_NULL) String timezone) {

    public TelemetryHistoryResponse(int requestedDays, int effectiveDays, boolean clamped,
                                    List<TelemetryDailyPoint> points) {
        this(requestedDays, effectiveDays, clamped, points, null, null);
    }
}
