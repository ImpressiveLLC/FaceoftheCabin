package com.cabin.orchestrator.weather;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutdoorDailyAggregatorTest {

    private static final Instant NOW = Instant.parse("2026-10-29T06:00:00Z");

    private static List<OutdoorObservation> obs(String station, String day, int n, double tempC, double rh) {
        List<OutdoorObservation> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            l.add(new OutdoorObservation("cabin", station, Instant.parse(day + "T00:00:00Z").plusSeconds(i * 600L),
                tempC, tempC - 5, rh));
        }
        return l;
    }

    @Test
    void averagesPerUtcDayAndPrefersPrimaryStation() {
        List<OutdoorObservation> all = new ArrayList<>();
        all.addAll(obs("UBE", "2026-10-20", 20, 10, 70));
        all.addAll(obs("RPD", "2026-10-20", 20, 12, 60));
        List<OutdoorDailyPoint> pts = OutdoorDailyAggregator.aggregate(all, List.of("UBE", "RPD"), 12, NOW);
        assertEquals(1, pts.size());
        assertEquals("UBE", pts.get(0).station());
        assertEquals(10.0, pts.get(0).tempC(), 1e-9);
        assertEquals(5.0, pts.get(0).dewpointC(), 1e-9);
        assertEquals(70.0, pts.get(0).rhPct(), 1e-9);
        assertEquals(20, pts.get(0).sampleCount());
        assertFalse(pts.get(0).partial());
    }

    @Test
    void fallsBackWhenPrimaryHasTooFewObservationsThatDay() {
        List<OutdoorObservation> all = new ArrayList<>();
        all.addAll(obs("UBE", "2026-10-21", 3, 10, 70));
        all.addAll(obs("RPD", "2026-10-21", 20, 12, 60));
        OutdoorDailyPoint p = OutdoorDailyAggregator.aggregate(all, List.of("UBE", "RPD"), 12, NOW).get(0);
        assertEquals("RPD", p.station());
        assertFalse(p.partial());
    }

    @Test
    void sparseDayWithNoGoodStationUsesBestCoveredAndIsPartial() {
        List<OutdoorObservation> all = new ArrayList<>();
        all.addAll(obs("UBE", "2026-10-22", 3, 10, 70));
        all.addAll(obs("RPD", "2026-10-22", 5, 12, 60));
        OutdoorDailyPoint p = OutdoorDailyAggregator.aggregate(all, List.of("UBE", "RPD"), 12, NOW).get(0);
        assertEquals("RPD", p.station());
        assertTrue(p.partial());
    }

    @Test
    void todayIsAlwaysPartialAndDaysAreSorted() {
        List<OutdoorObservation> all = new ArrayList<>();
        all.addAll(obs("UBE", "2026-10-29", 20, 8, 80));
        all.addAll(obs("UBE", "2026-10-28", 20, 9, 75));
        List<OutdoorDailyPoint> pts = OutdoorDailyAggregator.aggregate(all, List.of("UBE"), 12, NOW);
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"), pts.get(0).day());
        assertFalse(pts.get(0).partial());
        assertTrue(pts.get(1).partial());
    }

    @Test
    void nullReadingsAreIgnoredInTheMean() {
        List<OutdoorObservation> all = new ArrayList<>(obs("UBE", "2026-10-23", 12, 10, 70));
        all.add(new OutdoorObservation("cabin", "UBE", Instant.parse("2026-10-23T23:00:00Z"), null, null, null));
        OutdoorDailyPoint p = OutdoorDailyAggregator.aggregate(all, List.of("UBE"), 12, NOW).get(0);
        assertEquals(10.0, p.tempC(), 1e-9);
        assertEquals(13, p.sampleCount());
    }
}
