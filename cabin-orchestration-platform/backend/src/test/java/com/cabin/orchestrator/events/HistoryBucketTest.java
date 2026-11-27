package com.cabin.orchestrator.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class HistoryBucketTest {

    private static final ZoneId CENTRAL = ZoneId.of("America/Chicago");

    @Test
    void absentOrBlankIsTheDayAndAnUnknownValueIsRefused() {
        assertThat(HistoryBucket.parse(null)).contains(HistoryBucket.DAY);
        assertThat(HistoryBucket.parse("")).contains(HistoryBucket.DAY);
        assertThat(HistoryBucket.parse("1d")).contains(HistoryBucket.DAY);
        assertThat(HistoryBucket.parse("5m")).contains(HistoryBucket.FIVE_MINUTES);
        assertThat(HistoryBucket.parse("15m")).contains(HistoryBucket.FIFTEEN_MINUTES);
        assertThat(HistoryBucket.parse("1h")).contains(HistoryBucket.ONE_HOUR);
        assertThat(HistoryBucket.parse("1m")).isEmpty();
        assertThat(HistoryBucket.parse("5M")).isEmpty();
        assertThat(HistoryBucket.parse("1d,5m")).isEmpty();
    }

    @Test
    void bucketsStartOnTheNamedZonesWallClock() {
        Instant reading = Instant.parse("2026-11-27T20:37:12Z"); // 14:37:12 CST
        assertThat(HistoryBucket.FIVE_MINUTES.startOf(reading, CENTRAL)).isEqualTo(Instant.parse("2026-11-27T20:35:00Z"));
        assertThat(HistoryBucket.FIFTEEN_MINUTES.startOf(reading, CENTRAL)).isEqualTo(Instant.parse("2026-11-27T20:30:00Z"));
        assertThat(HistoryBucket.ONE_HOUR.startOf(reading, CENTRAL)).isEqualTo(Instant.parse("2026-11-27T20:00:00Z"));
        // +05:45: the hour starts at a quarter past in UTC, so the zone really is what decides.
        assertThat(HistoryBucket.ONE_HOUR.startOf(reading, ZoneId.of("Asia/Kathmandu")))
            .isEqualTo(Instant.parse("2026-11-27T20:15:00Z"));
    }

    @Test
    void theRepeatedHourWhenClocksFallBackStaysTwoBuckets() {
        Instant firstPass = Instant.parse("2026-11-01T06:30:00Z");  // 01:30 CDT
        Instant secondPass = Instant.parse("2026-11-01T07:30:00Z"); // 01:30 CST
        assertThat(HistoryBucket.ONE_HOUR.startOf(firstPass, CENTRAL)).isEqualTo(Instant.parse("2026-11-01T06:00:00Z"));
        assertThat(HistoryBucket.ONE_HOUR.startOf(secondPass, CENTRAL)).isEqualTo(Instant.parse("2026-11-01T07:00:00Z"));
    }
}
