package com.cabin.orchestrator.weather;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class OutdoorWeatherServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T06:00:00Z"), ZoneOffset.UTC);

    /** In-memory stand-in: no JdbcTemplate, so no Docker needed. */
    static class MemoryStore extends OutdoorWeatherStore {
        final List<OutdoorObservation> rows = new ArrayList<>();
        MemoryStore() { super(null); }
        @Override public int insertAll(List<OutdoorObservation> obs) {
            int added = 0;
            for (OutdoorObservation o : obs) {
                boolean dup = rows.stream().anyMatch(r -> r.location().equals(o.location())
                    && r.station().equals(o.station()) && r.observedAt().equals(o.observedAt()));
                if (!dup) { rows.add(o); added++; }
            }
            return added;
        }
        @Override public Instant latest(String location, String station) {
            return rows.stream().filter(r -> r.location().equals(location) && r.station().equals(station))
                .map(OutdoorObservation::observedAt).max(Instant::compareTo).orElse(null);
        }
        @Override public List<OutdoorObservation> since(String location, Instant since) {
            return rows.stream().filter(r -> r.location().equals(location) && !r.observedAt().isBefore(since)).toList();
        }
        @Override public List<OutdoorObservation> month(String location, String station, YearMonth m) {
            return rows.stream().filter(r -> r.location().equals(location) && r.station().equals(station)
                && YearMonth.from(r.observedAt().atZone(ZoneOffset.UTC)).equals(m)).toList();
        }
    }

    static String csv(String station, int n) {
        StringBuilder sb = new StringBuilder("station,valid,tmpf,dwpf,relh\n");
        for (int i = 0; i < n; i++) {
            sb.append(station).append(",2026-10-28 ").append(String.format("%02d", i / 3)).append(':')
              .append(String.format("%02d", (i % 3) * 20)).append(",50.00,40.00,70.00\n");
        }
        return sb.toString();
    }

    @Test
    void parsesStationSpec() {
        Map<String, List<String>> m = OutdoorWeatherService.parseStations("cabin=ube,rpd; home=msp ;bad;=x");
        assertEquals(List.of("UBE", "RPD"), m.get("cabin"));
        assertEquals(List.of("MSP"), m.get("home"));
        assertEquals(2, m.size());
        assertTrue(OutdoorWeatherService.parseStations("").isEmpty());
    }

    @Test
    void refreshStoresRowsAndWritesRawAndMonthlyLogToColdTier(@TempDir Path dir) throws Exception {
        MemoryStore store = new MemoryStore();
        WeatherFetcher f = (st, from, to) -> csv(st, 30);
        OutdoorWeatherService svc = new OutdoorWeatherService(store, f, CLOCK, true, "cabin=UBE", 60, dir.toString());
        assertEquals(30, svc.refresh("cabin", "UBE"));
        try (Stream<Path> s = Files.list(dir)) {
            List<String> names = s.map(p -> p.getFileName().toString()).toList();
            assertTrue(names.contains("outdoor_cabin_UBE_2026-10.csv"), names.toString());
        }
        String month = Files.readString(dir.resolve("outdoor_cabin_UBE_2026-10.csv"));
        assertTrue(month.startsWith("station,valid_utc,temp_c,dewpoint_c,rh_pct\n"));
        assertEquals(31, month.split("\n").length);
        try (Stream<Path> s = Files.list(dir.resolve("raw"))) {
            assertEquals(1, s.count());
        }
    }

    @Test
    void refetchingTheSameWindowAddsNothing(@TempDir Path dir) throws Exception {
        MemoryStore store = new MemoryStore();
        OutdoorWeatherService svc = new OutdoorWeatherService(store, (st, a, b) -> csv(st, 30), CLOCK, true,
            "cabin=UBE", 60, dir.toString());
        svc.refresh("cabin", "UBE");
        assertEquals(0, svc.refresh("cabin", "UBE"));
        assertEquals(30, store.rows.size());
    }

    @Test
    void fetchWindowStartsADayBeforeTheNewestStoredRow(@TempDir Path dir) throws Exception {
        MemoryStore store = new MemoryStore();
        List<LocalDate> froms = new ArrayList<>();
        WeatherFetcher f = (st, from, to) -> { froms.add(from); return csv(st, 30); };
        OutdoorWeatherService svc = new OutdoorWeatherService(store, f, CLOCK, true, "cabin=UBE", 60, dir.toString());
        svc.refresh("cabin", "UBE");
        svc.refresh("cabin", "UBE");
        assertEquals(LocalDate.of(2026, 8, 30), froms.get(0), "first run backfills backfillDays");
        assertEquals(LocalDate.of(2026, 10, 27), froms.get(1), "later runs only fetch the recent window");
    }

    @Test
    void aFailingFetchOrUnwritableArchiveNeverBreaksTheRun(@TempDir Path dir) throws Exception {
        MemoryStore store = new MemoryStore();
        OutdoorWeatherService failing = new OutdoorWeatherService(store,
            (st, a, b) -> { throw new IOException("network down"); }, CLOCK, true, "cabin=UBE", 60, dir.toString());
        assertDoesNotThrow(failing::refreshAll);
        assertTrue(store.rows.isEmpty());

        Path blocker = dir.resolve("not-a-dir");
        Files.writeString(blocker, "x");
        OutdoorWeatherService unwritable = new OutdoorWeatherService(store, (st, a, b) -> csv(st, 30), CLOCK, true,
            "cabin=UBE", 60, blocker.toString());
        assertDoesNotThrow(() -> { unwritable.refresh("cabin", "UBE"); });
        assertEquals(30, store.rows.size(), "the database copy is kept even when the cold-tier write fails");
    }

    @Test
    void historyReturnsEmptyForALocationWithNoStationAndClampsDays(@TempDir Path dir) throws Exception {
        MemoryStore store = new MemoryStore();
        OutdoorWeatherService svc = new OutdoorWeatherService(store, (st, a, b) -> csv(st, 30), CLOCK, true,
            "cabin=UBE", 60, dir.toString());
        OutdoorHistoryResponse none = svc.history("home", 30, 60);
        assertTrue(none.stations().isEmpty());
        assertTrue(none.points().isEmpty());

        svc.refresh("cabin", "UBE");
        OutdoorHistoryResponse r = svc.history("cabin", 500, 60);
        assertEquals(60, r.effectiveDays());
        assertTrue(r.clamped());
        assertEquals(1, r.points().size());
        assertEquals("UBE", r.points().get(0).station());
        assertEquals(10.0, r.points().get(0).tempC(), 0.01);
    }

    @Test
    void disabledServiceDoesNothing(@TempDir Path dir) {
        MemoryStore store = new MemoryStore();
        OutdoorWeatherService svc = new OutdoorWeatherService(store,
            (st, a, b) -> { fail("must not fetch when disabled"); return ""; }, CLOCK, false, "cabin=UBE", 60, dir.toString());
        svc.refreshAll();
        assertTrue(store.rows.isEmpty());
    }
}
