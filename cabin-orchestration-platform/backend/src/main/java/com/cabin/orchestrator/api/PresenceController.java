package com.cabin.orchestrator.api;

import com.cabin.orchestrator.presence.PresenceContractV1;
import com.cabin.orchestrator.presence.PresenceFreshness;
import com.cabin.orchestrator.presence.PresenceProfile;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/presence")
@CrossOrigin
public class PresenceController {

    private final PresenceService presenceService;
    private final PresenceSignalRegistry signalRegistry;

    // W-34: past this many hours without a phone heartbeat the badge reads
    // "Presence unknown" instead of Away / At Cabin. Same default as the siren
    // gate's own limit (W-33); configured separately.
    @Value("${cabin.presence.stale-after-hours:6}")
    private double staleAfterHours = 6;

    public PresenceController(PresenceService presenceService, PresenceSignalRegistry signalRegistry) {
        this.presenceService = presenceService;
        this.signalRegistry = signalRegistry;
    }

    @GetMapping
    public Map<String, Object> get() {
        PresenceProfile p = presenceService.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("profile", p.name());
        body.put("label", p.label());
        body.put("options", allOptions());
        // autoDerived: true once a real presence signal (see
        // PresenceSignalRegistry) has ever been seen -- the UI uses this
        // to show whether the pin reflects live detection or a manual
        // fallback, per the 2026-08-08 finding that a purely-manual value
        // masquerading as a live one drove real security severity
        // decisions with no actual signal behind it.
        body.put("autoDerived", presenceService.isAutoDerived());
        // W-34: how old the signal is. All four are null/false until the first
        // phone heartbeat has ever been seen, so nothing changes for a deploy
        // that has no heartbeat publisher yet. lastSeen is when the PHONE last
        // reported, not when the value last changed or when this backend
        // received a message. With several tracked people this is the newest
        // heartbeat among them.
        PresenceFreshness freshness = PresenceFreshness.of(signalRegistry.latestSeen(), Instant.now(), staleAfterHours);
        body.put("lastSeen", freshness.lastSeen() == null ? null : freshness.lastSeen().toString());
        body.put("signalAgeSeconds", freshness.signalAgeSeconds());
        body.put("stale", freshness.stale());
        body.put("staleAfterHours", freshness.staleAfterHours());
        body.put("signals", signalRegistry.all().stream()
            .map(s -> Map.of(
                "location", s.location(),
                "personId", s.personId(),
                "present", s.present(),
                "lastUpdated", s.lastUpdated().toString()))
            .toList());
        return body;
    }

    /**
     * D11's minimal aggregate presence assertion -- the only presence
     * shape Family Hub (or any other cross-domain caller) is allowed to
     * see. Deliberately a separate endpoint from GET /api/presence above,
     * not a change to that response's shape: the existing shape backs
     * cabin-ui's own PresenceToggle picker (profile/options/signals,
     * including personId) and changing it out from under that consumer
     * would be a real regression, not a refinement. See
     * PresenceContractV1's own javadoc for the full field-by-field
     * reasoning. Already covered by WebConfig's existing
     * "/api/presence/**" gate -- no separate wiring needed.
     */
    @GetMapping("/contract")
    public PresenceContractV1 contract() {
        return PresenceContractV1.of(presenceService.isAutoDerived(), signalRegistry.all(), Instant.now());
    }

    @PutMapping
    public Map<String, Object> set(@RequestBody Map<String, String> body) {
        PresenceProfile p = PresenceProfile.valueOf(body.get("profile"));
        presenceService.set(p);
        return Map.of("profile", p.name(), "label", p.label());
    }

    private List<Map<String, String>> allOptions() {
        return Arrays.stream(PresenceProfile.values())
            .map(p -> Map.of("value", p.name(), "label", p.label()))
            .toList();
    }
}
