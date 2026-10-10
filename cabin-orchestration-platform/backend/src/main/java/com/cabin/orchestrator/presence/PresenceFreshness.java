package com.cabin.orchestrator.presence;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * W-34: how old the presence signal is, and whether that is too old to trust.
 *
 * "Age" is the time since the phone last reported to Home Assistant (the
 * heartbeat, see PresenceSignalRegistry.recordSeen), not the time since the
 * presence VALUE last changed: presence is published only on change, so a value
 * can be days old and still be right.
 *
 * Inactive until a heartbeat has ever been seen: with none, age is unknown and
 * the signal is NOT called stale, so this can ship before the Home Assistant
 * side does without changing what anyone sees. Once heartbeats flow, a
 * publisher that stops ages out and reads stale.
 *
 * Same limit as the siren gate's (W-33), configured separately here
 * (cabin.presence.stale-after-hours, default 6).
 */
public record PresenceFreshness(Instant lastSeen, Long signalAgeSeconds, boolean stale, double staleAfterHours) {

    public static PresenceFreshness of(Optional<Instant> lastSeen, Instant now, double staleAfterHours) {
        if (lastSeen.isEmpty()) {
            return new PresenceFreshness(null, null, false, staleAfterHours);
        }
        // A heartbeat timestamped slightly in the future (clock skew) reads as age 0, never negative.
        long ageSeconds = Math.max(0, Duration.between(lastSeen.get(), now).getSeconds());
        boolean stale = ageSeconds > Math.round(staleAfterHours * 3600);
        return new PresenceFreshness(lastSeen.get(), ageSeconds, stale, staleAfterHours);
    }
}
