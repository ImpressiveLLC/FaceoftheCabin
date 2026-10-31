package com.cabin.orchestrator.weather;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MesonetCsvParserTest {

    private static final String CSV = """
        station,valid,tmpf,dwpf,relh
        UBE,2026-10-15 00:15,37.40,37.40,100.00
        UBE,2026-10-15 00:55,41.00,,
        UBE,2026-10-15 01:15,,,
        garbage line
        UBE,not-a-time,40,30,70
        """;

    @Test
    void parsesRowsAndConvertsToCelsius() {
        List<OutdoorObservation> obs = MesonetCsvParser.parse("cabin", CSV);
        assertEquals(2, obs.size(), "all-empty and malformed rows are skipped");
        OutdoorObservation first = obs.get(0);
        assertEquals("cabin", first.location());
        assertEquals("UBE", first.station());
        assertEquals(Instant.parse("2026-10-15T00:15:00Z"), first.observedAt());
        assertEquals(3.0, first.tempC(), 0.01);
        assertEquals(100.0, first.rhPct(), 0.001);
    }

    @Test
    void missingIndividualReadingsStayNull() {
        OutdoorObservation second = MesonetCsvParser.parse("cabin", CSV).get(1);
        assertEquals(5.0, second.tempC(), 0.01);
        assertNull(second.dewpointC());
        assertNull(second.rhPct());
    }

    @Test
    void nullOrEmptyInputGivesEmptyList() {
        assertTrue(MesonetCsvParser.parse("cabin", null).isEmpty());
        assertTrue(MesonetCsvParser.parse("cabin", "").isEmpty());
    }
}
