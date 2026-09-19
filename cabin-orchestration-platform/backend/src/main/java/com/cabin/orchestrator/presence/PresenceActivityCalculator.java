package com.cabin.orchestrator.presence;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns one occupancy sensor's activated/cleared transitions into the
 * per-day picture Security &amp; Presence shows. Pure (no I/O, no clock) so the
 * day-boundary, visit-grouping and duration rules are testable directly.
 *
 * <p>Definitions, stated once so the UI and docs can quote them:
 * <ul>
 *   <li><b>Activation</b> -- one ACTIVATED transition (a false-&gt;true edge).</li>
 *   <li><b>Visit</b> -- activations closer together than {@code visitGap}
 *       are one visit. A PIR sensor re-triggers every minute or so while
 *       someone moves around a room; a person in the room for an hour is one
 *       visit, not sixty activations.</li>
 *   <li><b>Active minutes</b> -- time the sensor reported occupancy=true,
 *       from ACTIVATED to the next CLEARED. Attributed to the day the
 *       activation started (a run across midnight is not split). An
 *       activation with no CLEARED yet counts up to {@code OPEN_CAP} or
 *       {@code now}, whichever is less, so a dropped CLEARED can't invent
 *       hours of presence.</li>
 *   <li>Days are local days in {@code zone}, not UTC.</li>
 * </ul>
 */
public final class PresenceActivityCalculator {

    static final Duration OPEN_CAP = Duration.ofMinutes(10);

    /** One stored transition. {@code activated} false means CLEARED. */
    public record Edge(Instant at, boolean activated) {}

    public record Day(LocalDate date, int activations, int visits, double activeMinutes, Instant firstAt, Instant lastAt) {}

    public record Summary(List<Day> days, int[] activationsByHour, int activations, int visits, double activeMinutes,
                          int activeDays, Instant firstActivation, Instant lastActivation, List<Instant> recent) {}

    private PresenceActivityCalculator() {}

    /**
     * @param edges     transitions for ONE sensor, ascending by time; may start before the window
     * @param windowEnd the local day the window ends on (inclusive), normally today
     * @param days      window length in days, ending at {@code windowEnd}
     */
    public static Summary summarize(List<Edge> edges, LocalDate windowEnd, int days, ZoneId zone,
                                    Duration visitGap, Instant now) {
        LocalDate windowStart = windowEnd.minusDays(days - 1L);
        Instant from = windowStart.atStartOfDay(zone).toInstant();

        // Pair each ACTIVATED with the CLEARED that ends it.
        List<Instant> starts = new ArrayList<>();
        List<Duration> lengths = new ArrayList<>();
        Instant open = null;
        for (Edge e : edges) {
            if (e.activated()) {
                if (open != null) continue; // repeat ACTIVATED with no CLEARED between: keep the first
                open = e.at();
            } else if (open != null) {
                starts.add(open);
                lengths.add(Duration.between(open, e.at()));
                open = null;
            }
        }
        if (open != null) {
            starts.add(open);
            Duration sinceOpen = Duration.between(open, now);
            lengths.add(sinceOpen.compareTo(OPEN_CAP) > 0 ? OPEN_CAP : sinceOpen.isNegative() ? Duration.ZERO : sinceOpen);
        }

        Map<LocalDate, int[]> counts = new LinkedHashMap<>();       // [activations, visits]
        Map<LocalDate, Double> minutes = new LinkedHashMap<>();
        Map<LocalDate, Instant[]> span = new LinkedHashMap<>();     // [first, last]
        int[] byHour = new int[24];
        List<Instant> inWindow = new ArrayList<>();

        Instant lastEnd = null;
        int visits = 0;
        double totalMinutes = 0;
        for (int i = 0; i < starts.size(); i++) {
            Instant start = starts.get(i);
            Instant end = start.plus(lengths.get(i));
            boolean newVisit = lastEnd == null || Duration.between(lastEnd, start).compareTo(visitGap) > 0;
            lastEnd = end;
            if (start.isBefore(from)) continue;

            LocalDate day = start.atZone(zone).toLocalDate();
            if (day.isAfter(windowEnd)) continue;
            int[] c = counts.computeIfAbsent(day, d -> new int[2]);
            c[0]++;
            if (newVisit) { c[1]++; visits++; }
            double m = lengths.get(i).toMillis() / 60000.0;
            minutes.merge(day, m, Double::sum);
            totalMinutes += m;
            Instant[] s = span.computeIfAbsent(day, d -> new Instant[] { start, start });
            s[1] = start;
            byHour[start.atZone(zone).getHour()]++;
            inWindow.add(start);
        }

        List<Day> out = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDate d = windowStart.plusDays(i);
            int[] c = counts.getOrDefault(d, new int[2]);
            Instant[] s = span.get(d);
            out.add(new Day(d, c[0], c[1], round1(minutes.getOrDefault(d, 0.0)),
                s == null ? null : s[0], s == null ? null : s[1]));
        }
        int activeDays = (int) out.stream().filter(d -> d.activations() > 0).count();
        Instant lastInWindow = inWindow.isEmpty() ? null : inWindow.get(inWindow.size() - 1);
        Instant firstInWindow = inWindow.isEmpty() ? null : inWindow.get(0);
        List<Instant> recent = new ArrayList<>(inWindow.subList(Math.max(0, inWindow.size() - 20), inWindow.size()));
        java.util.Collections.reverse(recent);
        return new Summary(out, byHour, inWindow.size(), visits, round1(totalMinutes), activeDays,
            firstInWindow, lastInWindow, recent);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
