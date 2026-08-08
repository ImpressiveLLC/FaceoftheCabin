package com.cabin.orchestrator.presence;

import org.junit.jupiter.api.Test;

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
}
