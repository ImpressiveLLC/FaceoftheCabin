package com.cabin.orchestrator.events;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * One day's min/avg/max for a single numeric telemetry field on one
 * device -- the shape a historical trend view wants, not a raw event
 * replay: cabin_event's ~10-15min sample interval makes weeks of raw
 * points impractical to ship/render. avg/min/max are null (not 0) for a
 * day with zero matching samples, so a chart can tell "no reading" apart
 * from "read as zero".
 *
 * W-21: {@code partial} is true for the single earliest day in a
 * requested window whenever that window's start (`since`) falls after
 * that day's own start (the database session's midnight, the same
 * boundary {@code date_trunc('day', ...)} buckets by) -- true in practice
 * for almost every request, since "N days ago from right now" essentially
 * never lands on an exact midnight. A partial day's avg is computed over
 * fewer hours than a full day's, so averaging it in as if it were
 * equivalent is misleading; the UI labels it "(partial)" rather than
 * silently excluding it, since the samples it does have are still real.
 *
 * W-173: the same shape carries an intraday bucket. {@code day} is then the instant the bucket starts,
 * {@code partial} marks the earliest bucket when the window starts inside it, and {@code maxAt} is the time
 * of the first reading that reached {@code max}. A day point has no {@code maxAt}, and the field is left out
 * of its JSON, so the day response is what it was.
 */
public record TelemetryDailyPoint(Instant day, Double avg, Double min, Double max, long sampleCount, boolean partial,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Instant maxAt) {

    public TelemetryDailyPoint(Instant day, Double avg, Double min, Double max, long sampleCount, boolean partial) {
        this(day, avg, min, max, sampleCount, partial, null);
    }
}
