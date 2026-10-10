package com.cabin.orchestrator.presence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** W-34: how old the presence signal is and when it is too old to trust. */
class PresenceFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    private static PresenceFreshness at(Instant seen, double limitHours) {
        return PresenceFreshness.of(Optional.ofNullable(seen), NOW, limitHours);
    }

    @Test
    void noHeartbeatEverSeenIsUnknownAgeAndNotStale() {
        PresenceFreshness f = at(null, 6);

        assertNull(f.lastSeen());
        assertNull(f.signalAgeSeconds());
        assertFalse(f.stale(),
            "with no heartbeat the age is unknown, and the badge must behave exactly as before this existed");
        assertEquals(6.0, f.staleAfterHours());
    }

    @Test
    void aRecentHeartbeatIsFreshAndReportsItsAge() {
        PresenceFreshness f = at(NOW.minusSeconds(3600), 6);

        assertEquals(NOW.minusSeconds(3600), f.lastSeen());
        assertEquals(3600L, f.signalAgeSeconds());
        assertFalse(f.stale());
    }

    @Test
    void exactlyAtTheLimitIsNotStaleButOneSecondPastIs() {
        assertFalse(at(NOW.minusSeconds(6 * 3600), 6).stale());
        assertTrue(at(NOW.minusSeconds(6 * 3600 + 1), 6).stale());
    }

    @Test
    void theLimitIsConfigurable() {
        assertFalse(at(NOW.minusSeconds(7 * 3600), 12).stale());
        assertTrue(at(NOW.minusSeconds(13 * 3600), 12).stale());
        assertTrue(at(NOW.minusSeconds(1800), 0.25).stale(), "a fractional-hour limit is honoured");
    }

    @Test
    void aHeartbeatFromTheFutureReadsAsAgeZeroNotNegative() {
        PresenceFreshness f = at(NOW.plusSeconds(300), 6); // clock skew between HA and this host

        assertEquals(0L, f.signalAgeSeconds());
        assertFalse(f.stale());
    }

    @Test
    void theFortyOneHourSilenceFromTheRealIncidentReadsStale() {
        // 2026-10-01 19:07 CDT was the phone's last report; it was ~41 h later
        // when "Away" was shown at the house.
        PresenceFreshness f = at(NOW.minusSeconds(41 * 3600), 6);

        assertTrue(f.stale());
        assertEquals(41L * 3600, f.signalAgeSeconds());
    }
}
