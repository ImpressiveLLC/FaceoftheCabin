package com.cabin.orchestrator.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live, in-memory armed/disarmed state per location -- keyed by location
 * so this is never hardcoded to cabin-only, matching
 * PresenceSignalRegistry's reasoning exactly (see that class's comment).
 *
 * Not Postgres-backed for the same reason presence isn't: this mirrors a
 * retained MQTT value that already self-heals (HA republishes
 * cabin/security/armed_away on every toggle AND on every HA restart --
 * see docs/ontology.yaml's automation_cabin_security_publish_arm_state
 * and automation_cabin_security_publish_presence_at_startup), so a
 * cabin-backend restart simply waits for that republish rather than
 * needing its own durable copy.
 */
@Component
public class SecurityStateRegistry {

    public record ArmState(String location, boolean armed, Instant lastUpdated) {}

    private final Map<String, ArmState> states = new ConcurrentHashMap<>();

    public void record(String location, boolean armed) {
        states.put(location, new ArmState(location, armed, Instant.now()));
    }

    public Optional<ArmState> get(String location) {
        return Optional.ofNullable(states.get(location));
    }

    public List<ArmState> all() {
        return states.values().stream().toList();
    }
}
