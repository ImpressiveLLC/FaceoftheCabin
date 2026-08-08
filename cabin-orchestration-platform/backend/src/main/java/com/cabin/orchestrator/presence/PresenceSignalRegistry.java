package com.cabin.orchestrator.presence;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live, in-memory presence signals -- one entry per (location, person)
 * pair, keyed loosely so this is never hardcoded to a single person or a
 * single location. Deliberately NOT Postgres-backed, matching
 * DeviceRegistry's reasoning: this is transient "who's here right now"
 * state, not a durable record -- a restart simply waits for the next
 * signal from each publisher (typically within a minute, matching the
 * underlying HA automations' scan_interval) rather than trusting a
 * possibly-stale row from before the restart.
 *
 * A location or person only ever appears here after its first real
 * signal arrives -- same "discovered, not preconfigured" pattern
 * MqttBridgeService already uses for devices and cameras. This is what
 * makes the design N-people x M-locations from day one: cabin has one
 * real signal today (cabin/presence/nate), but nothing here assumes
 * exactly one person or exactly one location -- home/presence/{anyone}
 * slots in with zero code changes once home has its own presence
 * automation.
 */
@Component
public class PresenceSignalRegistry {

    public record Signal(String location, String personId, boolean present, Instant lastUpdated) {}

    private final Map<String, Signal> signals = new ConcurrentHashMap<>();

    public void record(String location, String personId, boolean present) {
        signals.put(key(location, personId), new Signal(location, personId, present, Instant.now()));
    }

    public List<Signal> all() {
        return signals.values().stream().toList();
    }

    /** True if any tracked person currently has a "present" signal at this location. */
    public boolean anyPresentAt(String location) {
        return signals.values().stream()
            .anyMatch(s -> s.location().equals(location) && s.present());
    }

    /**
     * False until at least one real signal has ever arrived (no presence
     * automation configured for this instance/location yet) -- the
     * signal for PresenceService to keep trusting whatever the manual
     * fallback says, rather than confidently reporting "AWAY" because it
     * has literally never heard from anyone.
     */
    public boolean hasAnySignal() {
        return !signals.isEmpty();
    }

    private String key(String location, String personId) {
        return location + ":" + personId;
    }
}
