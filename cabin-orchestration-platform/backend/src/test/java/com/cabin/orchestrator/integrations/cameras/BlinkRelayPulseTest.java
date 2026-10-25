package com.cabin.orchestrator.integrations.cameras;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class BlinkRelayPulseTest {

    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void neverSeenIsNotStale() {
        BlinkRelayPulse p = new BlinkRelayPulse();
        assertTrue(p.age(NOW).isEmpty());
        assertFalse(p.stale(NOW));
    }

    @Test
    void freshHeartbeatIsNotStaleOldOneIs() {
        BlinkRelayPulse p = new BlinkRelayPulse();
        p.record("{\"device\":\"xperia\",\"ts\":\"2026-10-24T11:55:00Z\"}", NOW);
        assertEquals(Duration.ofMinutes(5), p.age(NOW).orElseThrow());
        assertFalse(p.stale(NOW));
        assertTrue(p.stale(NOW.plus(Duration.ofMinutes(6))));
    }

    @Test
    void plainIsoPayloadAndGarbageFallBackToArrivalTime() {
        BlinkRelayPulse p = new BlinkRelayPulse();
        p.record("2026-10-24T11:00:00Z", NOW);
        assertEquals(Duration.ofHours(1), p.age(NOW).orElseThrow());
        BlinkRelayPulse q = new BlinkRelayPulse();
        q.record("garbage", NOW);
        assertEquals(Duration.ZERO, q.age(NOW).orElseThrow());
    }

    @Test
    void olderHeartbeatNeverMovesTheClockBackwards() {
        BlinkRelayPulse p = new BlinkRelayPulse();
        p.record("2026-10-24T11:59:00Z", NOW);
        p.record("2026-10-24T11:00:00Z", NOW);  // stale retained replay
        assertEquals(Duration.ofMinutes(1), p.age(NOW).orElseThrow());
    }
}
