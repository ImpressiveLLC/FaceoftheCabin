package com.cabin.orchestrator.presence;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
 *
 * Location roles (D24, W-38 slice 1): a signal from a maintenance-only person is not counted -- they are a guest for
 * presence -- and its arrival is recorded as a MAINTENANCE_VISIT audit event instead. A signal from a person who is not in
 * the tracked-person registry is still counted (the siren gate fails safe and nothing silently stops being recognized) but
 * is listed by unregisteredCounted(). This changes what the hub counts and shows; the siren gate itself lives in Node-RED
 * and Home Assistant and is not changed here.
 */
@Component
public class PresenceSignalRegistry {

    private static final Logger log = LoggerFactory.getLogger(PresenceSignalRegistry.class);

    private final LocationRoleService roles;
    private final EventPublisher events;

    /** Without roles every signal is counted, as before W-38. */
    public PresenceSignalRegistry() {
        this(null, null);
    }

    @Autowired
    public PresenceSignalRegistry(LocationRoleService roles, EventPublisher events) {
        this.roles = roles;
        this.events = events;
    }

    public record Signal(String location, String personId, boolean present, Instant lastUpdated) {}

    private final Map<String, Signal> signals = new ConcurrentHashMap<>();

    private final Set<String> unregistered = ConcurrentHashMap.newKeySet();
    private final Map<String, Boolean> maintenancePresent = new ConcurrentHashMap<>();

    /** A live signal; see the four-argument form for what the result means. */
    public LocationRoleService.Treatment record(String location, String personId, boolean present) {
        return record(location, personId, present, false);
    }

    /**
     * Records one signal and returns how it was treated, so the caller can tell a counted signal from one that is not
     * (D24 R-LR-2): COUNTED, UNREGISTERED_COUNTED (counted and flagged) or MAINTENANCE_ONLY (a guest for presence: nothing
     * stored, and the caller must not announce it as a presence change). retained is true for a message the broker replays
     * on subscribe; a replay never writes a maintenance visit, so a restart or deploy does not log a visit that did not happen.
     */
    public LocationRoleService.Treatment record(String location, String personId, boolean present, boolean retained) {
        return record(location, personId, present, retained ? Arrival.NEVER : Arrival.ANY_NEW_PRESENCE);
    }

    /**
     * A polled state (the Home Assistant discovery pass), not a message. The first poll after a backend restart sees every
     * person's current state and cannot tell an arrival from "already there", so a visit is written only for a not-present
     * to present change the registry itself observed (W-129 item 1).
     */
    public LocationRoleService.Treatment recordSnapshot(String location, String personId, boolean present) {
        return record(location, personId, present, Arrival.OBSERVED_CHANGE);
    }

    private enum Arrival { ANY_NEW_PRESENCE, OBSERVED_CHANGE, NEVER }

    private LocationRoleService.Treatment record(String location, String personId, boolean present, Arrival arrival) {
        String key = key(location, personId);
        LocationRoleService.Treatment treatment = treatmentOf(location, personId);
        if (treatment == LocationRoleService.Treatment.MAINTENANCE_ONLY) {
            signals.remove(key);
            unregistered.remove(key);
            Boolean was = maintenancePresent.put(key, present);
            boolean arrived = present && !Boolean.TRUE.equals(was) && switch (arrival) {
                case ANY_NEW_PRESENCE -> true;
                case OBSERVED_CHANGE -> was != null;
                case NEVER -> false;
            };
            if (arrived) recordMaintenanceVisit(location, personId);
            return treatment;
        }
        maintenancePresent.remove(key);
        signals.put(key, new Signal(location, personId, present, Instant.now()));
        if (treatment == LocationRoleService.Treatment.UNREGISTERED_COUNTED) unregistered.add(key);
        else unregistered.remove(key);
        return treatment;
    }

    /**
     * Called after a person's roles at a location changed (W-129 item 2). The stored signal was recorded under the old
     * roles, so it is re-judged now: a person who has become maintenance-only is no longer counted, and if they were present
     * a departure (PRESENCE_CHANGED, present false) is published so presence-arrived workflows and the profile see them leave;
     * a person who has become registered stops being listed as unregistered at once, not at their next message.
     * Returns the treatment now in force.
     */
    public LocationRoleService.Treatment reevaluate(String location, String personId) {
        String key = key(location, personId);
        LocationRoleService.Treatment treatment = treatmentOf(location, personId);
        Signal stored = signals.get(key);
        if (treatment == LocationRoleService.Treatment.MAINTENANCE_ONLY) {
            if (stored != null) {
                signals.remove(key);
                unregistered.remove(key);
                maintenancePresent.put(key, stored.present());
                if (stored.present()) publishPresenceChanged(location, personId, false);
            }
        } else if (treatment == LocationRoleService.Treatment.UNREGISTERED_COUNTED) {
            maintenancePresent.remove(key);
            if (stored != null) unregistered.add(key);
        } else {
            maintenancePresent.remove(key);
            unregistered.remove(key);
        }
        return treatment;
    }

    private void publishPresenceChanged(String location, String personId, boolean present) {
        if (events == null) return;
        try {
            events.publish(new CabinEvent(UUID.randomUUID().toString(), "presence:" + location + ":" + personId, "PRESENCE_CHANGED",
                "INFO", Instant.now(), Map.of("present", present, "personId", personId, "location", location)));
        } catch (RuntimeException e) {
            log.warn("Could not publish presence change of {} at {}: {}", personId, location, e.getMessage());
        }
    }

    /** Counted signals from people who are not in the tracked-person registry yet. */
    public List<Signal> unregisteredCounted() {
        return signals.entrySet().stream().filter(e -> unregistered.contains(e.getKey())).map(Map.Entry::getValue).toList();
    }

    private LocationRoleService.Treatment treatmentOf(String location, String personId) {
        if (roles == null) return LocationRoleService.Treatment.COUNTED;
        try {
            return roles.treatmentOf(personId, location);
        } catch (RuntimeException e) {
            // A registry that cannot be read must never make a primary person stop counting.
            log.warn("Location role lookup failed for {} at {}; counting the signal: {}", personId, location, e.getMessage());
            return LocationRoleService.Treatment.COUNTED;
        }
    }

    /** R-LR-8: who, which location, when. Not a presence signal and not an alert. */
    private void recordMaintenanceVisit(String location, String personId) {
        if (events == null) return;
        try {
            events.publish(new CabinEvent(UUID.randomUUID().toString(), "presence:" + location, "MAINTENANCE_VISIT", "INFO",
                Instant.now(), Map.of("personId", personId, "location", location)));
        } catch (RuntimeException e) {
            log.warn("Could not record maintenance visit of {} at {}: {}", personId, location, e.getMessage());
        }
    }

    public List<Signal> all() {
        return signals.values().stream().toList();
    }

    // W-34: when each person's phone last reported, taken from the heartbeat
    // Home Assistant publishes (cabin/presence/{personId}/last_seen, W-23).
    // Deliberately separate from Signal.lastUpdated: that is when THIS BACKEND
    // received a message, and a backend restart re-delivers retained messages
    // and would reset it to "just now" for a phone that has been silent for
    // days. The heartbeat carries the real report time.
    private final Map<String, Instant> seenByPerson = new ConcurrentHashMap<>();

    /** A heartbeat from a maintenance-only person says nothing about the location's occupants, so it is not kept. */
    public void recordSeen(String location, String personId, Instant seenAt) {
        if (treatmentOf(location, personId) == LocationRoleService.Treatment.MAINTENANCE_ONLY) return;
        recordSeen(personId, seenAt);
    }

    /** Keeps the newest heartbeat per person; an older timestamp never replaces a newer one. */
    public void recordSeen(String personId, Instant seenAt) {
        seenByPerson.merge(personId, seenAt, (current, incoming) -> incoming.isAfter(current) ? incoming : current);
    }

    /** The most recent heartbeat across everyone tracked; empty until the first one has ever arrived. */
    public java.util.Optional<Instant> latestSeen() {
        return seenByPerson.values().stream().max(java.util.Comparator.naturalOrder());
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
