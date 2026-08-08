package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.SecurityStateRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surfaces the real, HA-published armed/disarmed state
 * (cabin/security/armed_away, see SecurityStateRegistry) -- added
 * 2026-08-08 alongside the presence-derivation fix, same reasoning: a
 * real signal already existed and cabin-backend had simply never
 * subscribed to it, leaving the UI with no way to answer "is this
 * actually armed" for a user looking at an ambiguous alert.
 */
@RestController
@RequestMapping("/api/security")
@CrossOrigin
public class SecurityController {

    private final SecurityStateRegistry registry;

    public SecurityController(SecurityStateRegistry registry) {
        this.registry = registry;
    }

    /**
     * Keyed by location, e.g. {"cabin": {"armed": true, "lastUpdated": "..."}}.
     * A location with no entry means no armed_away signal has ever been
     * seen for it yet (home-hub isn't deployed, so "home" is absent
     * today) -- the UI must treat that as unknown, not as disarmed.
     */
    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> body = new LinkedHashMap<>();
        for (var state : registry.all()) {
            body.put(state.location(), Map.of(
                "armed", state.armed(),
                "lastUpdated", state.lastUpdated().toString()));
        }
        return body;
    }
}
