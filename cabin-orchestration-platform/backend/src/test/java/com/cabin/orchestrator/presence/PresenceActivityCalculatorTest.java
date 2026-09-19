package com.cabin.orchestrator.presence;

import com.cabin.orchestrator.presence.PresenceActivityCalculator.Edge;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The definitions in PresenceActivityCalculator's javadoc (activation, visit,
 * active minutes, local days) are what Security &amp; Presence shows a household;
 * these pin each one. Zone is America/Chicago (UTC-5 in September) because the
 * day-boundary rule is the one most likely to be silently wrong.
 */
class PresenceActivityCalculatorTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final LocalDate END = LocalDate.of(2026, 9, 10);
    private static final Instant NOW = Instant.parse("2026-09-10T20:00:00Z");
    private static final Duration GAP = Duration.ofMinutes(30);

    private static Edge on(String iso) { return new Edge(Instant.parse(iso), true); }
    private static Edge off(String iso) { return new Edge(Instant.parse(iso), false); }

    private static PresenceActivityCalculator.Summary run(int days, Edge... edges) {
        return PresenceActivityCalculator.summarize(List.of(edges), END, days, CHICAGO, GAP, NOW);
    }

    @Test
    void everyDayInTheWindowIsPresentEvenWithNoActivity() {
        var s = run(3);

        assertEquals(3, s.days().size());
        assertEquals(LocalDate.of(2026, 9, 8), s.days().get(0).date());
        assertEquals(LocalDate.of(2026, 9, 10), s.days().get(2).date());
        assertTrue(s.days().stream().allMatch(d -> d.activations() == 0 && d.firstAt() == null));
        assertEquals(0, s.activations());
        assertEquals(0, s.activeDays());
        assertNull(s.firstActivation());
        assertNull(s.lastActivation());
    }

    @Test
    void activationsCloserThanTheGapAreOneVisit() {
        var s = run(3,
            on("2026-09-09T14:00:00Z"), off("2026-09-09T14:01:00Z"),
            on("2026-09-09T14:10:00Z"), off("2026-09-09T14:12:00Z"),   // 9 min after the last clear: same visit
            on("2026-09-09T15:00:00Z"), off("2026-09-09T15:01:00Z"));  // 48 min later: a new visit

        assertEquals(3, s.activations());
        assertEquals(2, s.visits());
        assertEquals(2, s.days().get(1).visits());
        assertEquals(4.0, s.activeMinutes(), 0.001, "1 + 2 + 1 minutes occupied");
    }

    @Test
    void daysAreLocalNotUtc() {
        // 04:59Z is 23:59 CDT on Sep 8; 05:01Z is 00:01 CDT on Sep 9.
        var s = run(3,
            on("2026-09-09T04:59:00Z"), off("2026-09-09T05:00:00Z"),
            on("2026-09-09T05:01:00Z"), off("2026-09-09T05:02:00Z"));

        assertEquals(1, s.days().get(0).activations(), "Sep 8 local");
        assertEquals(1, s.days().get(1).activations(), "Sep 9 local");
        assertEquals(1, s.activationsByHour()[23]);
        assertEquals(1, s.activationsByHour()[0]);
    }

    @Test
    void anActivationBeforeTheWindowIsNotCounted() {
        // Window starts Sep 8 00:00 CDT = 05:00Z.
        var s = run(3, on("2026-09-08T04:59:00Z"), off("2026-09-08T05:00:30Z"));

        assertEquals(0, s.activations());
    }

    @Test
    void anUnclosedActivationCountsAtMostTheOpenCap() {
        var stale = run(1, on("2026-09-10T19:00:00Z"));
        var fresh = run(1, on("2026-09-10T19:57:00Z"));

        assertEquals(10.0, stale.activeMinutes(), 0.001, "a dropped CLEARED can't invent an hour of presence");
        assertEquals(3.0, fresh.activeMinutes(), 0.001, "still open 3 min ago: counted to now");
    }

    @Test
    void aRepeatedActivatedWithoutAClearedKeepsTheFirstOne() {
        var s = run(1, on("2026-09-10T14:00:00Z"), on("2026-09-10T14:00:30Z"), off("2026-09-10T14:02:00Z"));

        assertEquals(1, s.activations());
        assertEquals(2.0, s.activeMinutes(), 0.001);
    }

    @Test
    void aClearedWithNothingOpenIsIgnored() {
        var s = run(1, off("2026-09-10T14:00:00Z"), on("2026-09-10T15:00:00Z"), off("2026-09-10T15:01:00Z"));

        assertEquals(1, s.activations());
    }

    @Test
    void firstAndLastAtSpanTheDay() {
        var s = run(1,
            on("2026-09-10T13:00:00Z"), off("2026-09-10T13:01:00Z"),
            on("2026-09-10T18:30:00Z"), off("2026-09-10T18:31:00Z"));

        var day = s.days().get(0);
        assertEquals(Instant.parse("2026-09-10T13:00:00Z"), day.firstAt());
        assertEquals(Instant.parse("2026-09-10T18:30:00Z"), day.lastAt());
    }

    @Test
    void recentIsNewestFirstAndCappedAtTwenty() {
        List<Edge> edges = new ArrayList<>();
        Instant t = Instant.parse("2026-09-09T05:00:00Z");
        for (int i = 0; i < 25; i++) {
            edges.add(new Edge(t.plus(Duration.ofHours(i)), true));
            edges.add(new Edge(t.plus(Duration.ofHours(i)).plusSeconds(30), false));
        }
        var s = PresenceActivityCalculator.summarize(edges, END, 3, CHICAGO, GAP, NOW);

        assertEquals(25, s.activations());
        assertEquals(20, s.recent().size());
        assertEquals(t.plus(Duration.ofHours(24)), s.recent().get(0));
        assertTrue(s.recent().get(0).isAfter(s.recent().get(1)));
    }

    @Test
    void activeDaysCountsOnlyDaysWithActivity() {
        var s = run(3, on("2026-09-08T15:00:00Z"), off("2026-09-08T15:01:00Z"),
                       on("2026-09-10T15:00:00Z"), off("2026-09-10T15:01:00Z"));

        assertEquals(2, s.activeDays());
        assertEquals(0, s.days().get(1).activations());
    }
}
