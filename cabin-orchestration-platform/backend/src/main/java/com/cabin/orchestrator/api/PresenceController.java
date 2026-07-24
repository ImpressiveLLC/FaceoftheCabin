package com.cabin.orchestrator.api;

import com.cabin.orchestrator.presence.PresenceProfile;
import com.cabin.orchestrator.presence.PresenceService;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/presence")
@CrossOrigin
public class PresenceController {

    private final PresenceService presenceService;

    public PresenceController(PresenceService presenceService) {
        this.presenceService = presenceService;
    }

    @GetMapping
    public Map<String, Object> get() {
        PresenceProfile p = presenceService.get();
        return Map.of("profile", p.name(), "label", p.label(), "options", allOptions());
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
