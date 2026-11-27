package com.cabin.orchestrator.events;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

/**
 * W-173: the resolutions {@code GET /api/events/telemetry-history} can answer in. {@link #DAY} is the
 * default and keeps the day-bucketed query it always had. The others are intraday buckets aligned to the
 * wall clock of one named zone ({@code cabin.timezone}), never the JVM default or the database session's.
 */
public enum HistoryBucket {
    FIVE_MINUTES("5m", 300),
    FIFTEEN_MINUTES("15m", 900),
    ONE_HOUR("1h", 3600),
    DAY("1d", 86400);

    private final String code;
    private final int seconds;

    HistoryBucket(String code, int seconds) {
        this.code = code;
        this.seconds = seconds;
    }

    public String code() {
        return code;
    }

    public int seconds() {
        return seconds;
    }

    public boolean intraday() {
        return this != DAY;
    }

    /** Absent or blank means {@link #DAY}. Anything not listed is empty, never silently a default. */
    public static Optional<HistoryBucket> parse(String value) {
        if (value == null || value.isBlank()) return Optional.of(DAY);
        for (HistoryBucket b : values()) {
            if (b.code.equals(value)) return Optional.of(b);
        }
        return Optional.empty();
    }

    /**
     * Start of the intraday bucket holding {@code instant}: the zone's wall clock floored to the bucket
     * size, using the offset in force at that instant, so the repeated hour when clocks fall back stays two
     * separate buckets. The SQL in {@code CabinEventService.intradayAggregates()} is the same arithmetic.
     */
    public Instant startOf(Instant instant, ZoneId zone) {
        long offset = zone.getRules().getOffset(instant).getTotalSeconds();
        long local = instant.getEpochSecond() + offset;
        return Instant.ofEpochSecond(Math.floorDiv(local, (long) seconds) * seconds - offset);
    }
}
