package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecurityStateRegistryTest {

    @Test
    void unknownLocationHasNoRecordedState() {
        SecurityStateRegistry registry = new SecurityStateRegistry();

        assertTrue(registry.get("cabin").isEmpty(),
            "a location with no armed_away signal yet must read as absent/unknown, not a default value");
    }

    @Test
    void recordsAndReturnsTheLatestStateForALocation() {
        SecurityStateRegistry registry = new SecurityStateRegistry();

        registry.record("cabin", true);
        assertTrue(registry.get("cabin").orElseThrow().armed());

        registry.record("cabin", false);
        assertFalse(registry.get("cabin").orElseThrow().armed(),
            "a later message must replace the previous state, not accumulate");
    }

    @Test
    void differentLocationsAreTrackedIndependently() {
        SecurityStateRegistry registry = new SecurityStateRegistry();

        registry.record("cabin", true);
        registry.record("home", false);

        assertTrue(registry.get("cabin").orElseThrow().armed());
        assertFalse(registry.get("home").orElseThrow().armed());
        assertEquals(2, registry.all().size());
    }
}
