package com.cabin.orchestrator.events;

import java.time.Instant;

/**
 * One day's min/avg/max for a single numeric telemetry field on one
 * device -- the shape a historical trend view wants, not a raw event
 * replay: cabin_event's ~10-15min sample interval makes weeks of raw
 * points impractical to ship/render. avg/min/max are null (not 0) for a
 * day with zero matching samples, so a chart can tell "no reading" apart
 * from "read as zero".
 */
public record TelemetryDailyPoint(Instant day, Double avg, Double min, Double max, long sampleCount) {}
