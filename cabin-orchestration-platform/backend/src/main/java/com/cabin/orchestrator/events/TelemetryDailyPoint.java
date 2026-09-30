package com.cabin.orchestrator.events;

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
 * that day's own UTC midnight -- true in practice for almost every
 * request, since "N days ago from right now" essentially never lands on
 * an exact midnight boundary. A partial day's avg is computed over
 * fewer hours than a full day's, so averaging it in as if it were
 * equivalent is misleading; the UI labels it "(partial)" rather than
 * silently excluding it, since the samples it does have are still real.
 */
public record TelemetryDailyPoint(Instant day, Double avg, Double min, Double max, long sampleCount, boolean partial) {}
