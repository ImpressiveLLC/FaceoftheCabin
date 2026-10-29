package com.cabin.orchestrator.weather;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns raw observations into one point per UTC day, choosing the station per day:
 * the first configured station (primary first, then fallbacks) that has at least
 * {@code minObsPerDay} observations that day. A day no station covers well still
 * shows the best-covered station's partial data, flagged by {@code partial}, instead of
 * a silent gap. Pure, so the choice is tested without a database.
 */
public final class OutdoorDailyAggregator {

    private OutdoorDailyAggregator() {}

    public static List<OutdoorDailyPoint> aggregate(List<OutdoorObservation> obs, List<String> stationOrder,
                                                    int minObsPerDay, Instant now) {
        Map<Instant, Map<String, List<OutdoorObservation>>> byDay = new TreeMap<>();
        for (OutdoorObservation o : obs) {
            Instant day = o.observedAt().truncatedTo(ChronoUnit.DAYS);
            byDay.computeIfAbsent(day, d -> new LinkedHashMap<>())
                 .computeIfAbsent(o.station(), s -> new ArrayList<>()).add(o);
        }
        Instant today = now.truncatedTo(ChronoUnit.DAYS);
        List<OutdoorDailyPoint> points = new ArrayList<>();
        for (Map.Entry<Instant, Map<String, List<OutdoorObservation>>> e : byDay.entrySet()) {
            String chosen = null;
            for (String s : stationOrder) {
                List<OutdoorObservation> l = e.getValue().get(s);
                if (l != null && l.size() >= minObsPerDay) { chosen = s; break; }
            }
            if (chosen == null) {
                int best = 0;
                for (String s : stationOrder) {
                    List<OutdoorObservation> l = e.getValue().get(s);
                    if (l != null && l.size() > best) { best = l.size(); chosen = s; }
                }
            }
            if (chosen == null) continue;
            List<OutdoorObservation> l = e.getValue().get(chosen);
            boolean partial = l.size() < minObsPerDay || !e.getKey().isBefore(today);
            points.add(new OutdoorDailyPoint(e.getKey(), chosen,
                mean(l, 0), mean(l, 1), mean(l, 2), l.size(), partial));
        }
        return points;
    }

    private static Double mean(List<OutdoorObservation> l, int which) {
        double sum = 0; int n = 0;
        for (OutdoorObservation o : l) {
            Double v = which == 0 ? o.tempC() : which == 1 ? o.dewpointC() : o.rhPct();
            if (v != null) { sum += v; n++; }
        }
        return n == 0 ? null : sum / n;
    }
}
