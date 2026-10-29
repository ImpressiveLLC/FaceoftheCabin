package com.cabin.orchestrator.weather;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MesonetHttpFetcherTest {
    @Test
    void urlAsksForUtcOnlyTheThreeFieldsAndTheDateWindow() {
        String u = MesonetHttpFetcher.url("https://x/asos.py", "UBE", LocalDate.of(2026, 8, 30), LocalDate.of(2026, 10, 30));
        assertTrue(u.contains("station=UBE"));
        assertTrue(u.contains("data=tmpf&data=dwpf&data=relh"));
        assertTrue(u.contains("year1=2026&month1=8&day1=30"));
        assertTrue(u.contains("year2=2026&month2=10&day2=30"));
        assertTrue(u.contains("tz=UTC"));
        assertTrue(u.contains("format=onlycomma"));
    }
}
