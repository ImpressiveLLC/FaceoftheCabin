package com.cabin.orchestrator.presence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Focused coverage of PresenceService's derivation logic itself,
 * separate from MqttBridgeServiceTest's coverage of the MQTT plumbing
 * that calls it. See PresenceService's own class comment for the
 * 2026-08-08 finding this exists to prevent recurring: a manual-only
 * value masquerading as live detection, feeding real security-severity
 * decisions with nothing real behind it.
 */
class PresenceServiceTest {

    private PresenceSignalRegistry signalRegistry;
    private PresenceService service;

    @BeforeEach
    void setUp() {
        signalRegistry = new PresenceSignalRegistry();
        service = new PresenceService(mock(JdbcTemplate.class), signalRegistry);
    }

    @Test
    void recomputeIsANoOpUntilAnyRealSignalHasEverArrived() {
        service.set(PresenceProfile.AWAY); // e.g. this instance has no presence automation configured

        PresenceProfile result = service.recomputeFromSignals();

        assertEquals(PresenceProfile.AWAY, result,
            "with zero signals ever seen, recompute must not confidently report a derived state");
        assertFalse(service.isAutoDerived(),
            "the value is still whatever was manually set -- must not claim to be auto-derived");
    }

    @Test
    void onceAnySignalArrivesRecomputeStopsBeingANoOp() {
        signalRegistry.record("cabin", "nate", true);

        PresenceProfile result = service.recomputeFromSignals();

        assertEquals(PresenceProfile.AT_CABIN, result);
        assertTrue(service.isAutoDerived());
    }

    @Test
    void noOnePresentAnywhereAfterAtLeastOneSignalSeenDerivesAway() {
        signalRegistry.record("cabin", "nate", false);

        assertEquals(PresenceProfile.AWAY, service.recomputeFromSignals());
    }

    @Test
    void defaultProfileBeforeAnyInitOrSignalIsAtHome() {
        // Matches the field initializer / the DB default row's value
        // (AT_HOME) -- this is the safe-side default for a brand new
        // instance that hasn't loaded its persisted value yet.
        assertEquals(PresenceProfile.AT_HOME, service.get());
        assertFalse(service.isAutoDerived());
    }
}
