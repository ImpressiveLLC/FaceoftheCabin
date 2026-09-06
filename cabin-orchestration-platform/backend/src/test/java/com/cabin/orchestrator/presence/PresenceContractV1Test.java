package com.cabin.orchestrator.presence;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresenceContractV1Test {

    private static final Instant NOW = Instant.parse("2026-09-06T14:22:00Z");

    @Test
    void reportsUnknownWhenProfileIsManualNotAutoDerived() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", true, NOW));

        PresenceContractV1 result = PresenceContractV1.of(false, signals, NOW);

        assertEquals("unknown", result.state());
        assertEquals(0.0, result.confidence());
        assertEquals(null, result.observedAt());
        assertEquals(null, result.expiresAt());
        assertTrue(result.sources().isEmpty());
        assertEquals("1", result.policyVersion());
    }

    @Test
    void reportsUnknownWhenAutoDerivedButNoSignalsExist() {
        PresenceContractV1 result = PresenceContractV1.of(true, List.of(), NOW);

        assertEquals("unknown", result.state());
        assertEquals(0.0, result.confidence());
    }

    @Test
    void reportsHomeWhenAFreshCabinSignalIsPresent() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", true, NOW.minus(Duration.ofMinutes(5))));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertEquals("home", result.state());
        assertEquals(1.0, result.confidence());
        assertEquals(NOW.minus(Duration.ofMinutes(5)).toString(), result.observedAt());
        assertEquals(NOW.minus(Duration.ofMinutes(5)).plus(Duration.ofMinutes(30)).toString(), result.expiresAt());
        assertEquals(List.of("wifi_presence"), result.sources());
    }

    @Test
    void reportsAwayWhenAllFreshSignalsAreNotPresent() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", false, NOW.minus(Duration.ofMinutes(1))));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertEquals("away", result.state());
        assertEquals(1.0, result.confidence());
    }

    @Test
    void mapsHomeLocationSignalsToPhoneGpsSource() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("home", "nate", true, NOW.minus(Duration.ofMinutes(2))));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertEquals(List.of("phone_gps"), result.sources());
    }

    @Test
    void combinesDistinctSourcesAcrossLocationsWithoutDuplicates() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", true, NOW.minus(Duration.ofMinutes(2))),
            new PresenceSignalRegistry.Signal("cabin", "emma", false, NOW.minus(Duration.ofMinutes(1))),
            new PresenceSignalRegistry.Signal("home", "nate", false, NOW.minus(Duration.ofMinutes(3))));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertEquals(List.of("phone_gps", "wifi_presence"), result.sources());
    }

    @Test
    void reportsUnknownWhenTheMostRecentSignalIsOlderThanTheThirtyMinuteFreshnessWindow() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", true, NOW.minus(Duration.ofMinutes(31))));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertEquals("unknown", result.state());
        assertEquals(0.0, result.confidence());
        assertTrue(result.sources().isEmpty());
    }

    @Test
    void treatsASignalExactlyAtTheFreshnessBoundaryAsStillFresh() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", true, NOW.minus(Duration.ofMinutes(30))));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertEquals("home", result.state());
    }

    @Test
    void usesTheMostRecentSignalsTimestampAsObservedAtWhenSignalsDisagreeOnAge() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", true, NOW.minus(Duration.ofMinutes(20))),
            new PresenceSignalRegistry.Signal("cabin", "emma", true, NOW.minus(Duration.ofMinutes(2))));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertEquals(NOW.minus(Duration.ofMinutes(2)).toString(), result.observedAt());
    }

    @Test
    void neverIncludesAPersonIdAnywhereInTheContract() {
        List<PresenceSignalRegistry.Signal> signals = List.of(
            new PresenceSignalRegistry.Signal("cabin", "nate", true, NOW));

        PresenceContractV1 result = PresenceContractV1.of(true, signals, NOW);

        assertTrue(result.sources().stream().noneMatch(s -> s.contains("nate")));
        assertTrue(result.toString().toLowerCase().indexOf("nate") < 0);
    }

    @Test
    void unknownFactoryMatchesOfWithNoSignals() {
        assertEquals(PresenceContractV1.unknown(), PresenceContractV1.of(true, List.of(), NOW));
    }
}
