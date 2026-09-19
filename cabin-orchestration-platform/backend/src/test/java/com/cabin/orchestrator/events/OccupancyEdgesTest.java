package com.cabin.orchestrator.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class OccupancyEdgesTest {

    private static final Instant AT = Instant.parse("2026-09-15T14:03:22.123456Z");

    @Test
    void falseToTrueIsAnActivation() {
        CabinEvent e = OccupancyEdges.edge("z2m-motion_entry", false, true, AT, "cabin").orElseThrow();

        assertEquals(OccupancyEdges.ACTIVATED, e.eventType());
        assertEquals("z2m-motion_entry", e.sourceDeviceId());
        assertEquals(AT, e.timestamp());
        assertEquals("INFO", e.severity());
        assertEquals("cabin", e.payload().get("location"));
    }

    @Test
    void trueToFalseIsAClear() {
        CabinEvent e = OccupancyEdges.edge("z2m-motion_entry", true, false, AT, "cabin").orElseThrow();

        assertEquals(OccupancyEdges.CLEARED, e.eventType());
    }

    @Test
    void anUnknownPreviousValueMakesTrueAnActivationButFalseNothing() {
        assertEquals(OccupancyEdges.ACTIVATED,
            OccupancyEdges.edge("d", null, true, AT, null).orElseThrow().eventType());
        assertTrue(OccupancyEdges.edge("d", null, false, AT, null).isEmpty(),
            "a first-ever 'false' is not a transition");
    }

    @Test
    void aRepeatOfTheSameValueIsNotAnEdge() {
        // The whole point: Z2M re-sends occupancy on every battery/linkquality report.
        assertEquals(Optional.empty(), OccupancyEdges.edge("d", true, true, AT, null));
        assertEquals(Optional.empty(), OccupancyEdges.edge("d", false, false, AT, null));
    }

    @Test
    void aMissingCurrentValueIsNotAnEdge() {
        assertTrue(OccupancyEdges.edge("d", true, null, AT, null).isEmpty());
    }

    @Test
    void theIdIsDeterministicAndMillisecondTruncated() {
        String live = OccupancyEdges.edge("z2m-motion_entry", false, true, AT, null).orElseThrow().eventId();

        assertEquals("occ:z2m-motion_entry:" + AT.toEpochMilli() + ":A", live);
        assertEquals(live, OccupancyEdges.eventId("z2m-motion_entry", AT, OccupancyEdges.ACTIVATED));
        assertTrue(OccupancyEdges.eventId("d", AT, OccupancyEdges.CLEARED).endsWith(":C"));
    }

    @Test
    void theEventTypesDoNotCollideWithTheCameraEventsPrefixes() {
        // CameraEventsPanel claims DETECTION_ and MOTION_; these must never appear in it.
        assertFalse(OccupancyEdges.ACTIVATED.startsWith("MOTION_") || OccupancyEdges.ACTIVATED.startsWith("DETECTION_"));
        assertFalse(OccupancyEdges.CLEARED.startsWith("MOTION_") || OccupancyEdges.CLEARED.startsWith("DETECTION_"));
    }
}
