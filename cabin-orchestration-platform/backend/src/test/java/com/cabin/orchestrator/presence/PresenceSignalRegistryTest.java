package com.cabin.orchestrator.presence;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PresenceSignalRegistryTest {

    @Test
    void hasNoSignalUntilTheFirstOneIsRecorded() {
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        assertFalse(registry.hasAnySignal());

        registry.record("cabin", "nate", true);

        assertTrue(registry.hasAnySignal());
    }

    @Test
    void anyPresentAtIsFalseForALocationWithNoSignalsAtAll() {
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        registry.record("cabin", "nate", true);

        assertFalse(registry.anyPresentAt("home"),
            "a location that has never published a signal must read as not-present, not throw or default true");
    }

    @Test
    void latestSignalForAPersonReplacesTheirPrevious() {
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        registry.record("cabin", "nate", true);
        registry.record("cabin", "nate", false);

        assertFalse(registry.anyPresentAt("cabin"));
        assertEquals(1, registry.all().size(), "the same person at the same location must update in place, not accumulate");
    }

    @Test
    void differentPeopleAtTheSameLocationAreTrackedIndependently() {
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        registry.record("cabin", "nate", false);
        registry.record("cabin", "emma", true);

        assertTrue(registry.anyPresentAt("cabin"), "emma alone being present is enough for the location to read as occupied");
        assertEquals(2, registry.all().size());
    }

    @Test
    void sameLocationSpelledConsistentlyAcrossPeopleIsKeyedCorrectly() {
        // Guards against a key-collision bug: "cabin:nate" and "cabinemma:e"
        // (or similar concatenation ambiguity) must never collide.
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        registry.record("cabin", "nate", true);
        registry.record("cab", "innate", true); // adversarial-ish but distinct location/person split

        assertEquals(2, registry.all().size());
        assertTrue(registry.anyPresentAt("cabin"));
        assertTrue(registry.anyPresentAt("cab"));
    }

    // ── W-34: phone heartbeat (when the phone last reported) ──────────────

    @Test
    void noHeartbeatEverSeenReadsEmpty() {
        assertTrue(new PresenceSignalRegistry().latestSeen().isEmpty());
    }

    @Test
    void aHeartbeatIsRecordedPerPersonAndTheNewestWins() {
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        Instant earlier = Instant.parse("2026-10-04T08:00:00Z");
        Instant later = Instant.parse("2026-10-04T09:00:00Z");

        registry.recordSeen("nate", earlier);
        registry.recordSeen("nate", later);

        assertEquals(later, registry.latestSeen().orElseThrow());
    }

    @Test
    void anOlderHeartbeatNeverReplacesANewerOne() {
        // A retained replay or out-of-order delivery must not make the phone
        // look like it last reported earlier than it did.
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        Instant later = Instant.parse("2026-10-04T09:00:00Z");

        registry.recordSeen("nate", later);
        registry.recordSeen("nate", Instant.parse("2026-10-03T00:00:00Z"));

        assertEquals(later, registry.latestSeen().orElseThrow());
    }

    @Test
    void latestSeenIsTheNewestAcrossPeople() {
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        registry.recordSeen("emma", Instant.parse("2026-10-04T07:00:00Z"));
        registry.recordSeen("nate", Instant.parse("2026-10-04T10:00:00Z"));

        assertEquals(Instant.parse("2026-10-04T10:00:00Z"), registry.latestSeen().orElseThrow());
    }

    @Test
    void aHeartbeatIsNotAPresenceSignal() {
        PresenceSignalRegistry registry = new PresenceSignalRegistry();
        registry.recordSeen("nate", Instant.now());

        assertFalse(registry.hasAnySignal(),
            "a heartbeat must not make PresenceService think a presence signal exists and start deriving AWAY");
        assertTrue(registry.all().isEmpty());
    }
}
