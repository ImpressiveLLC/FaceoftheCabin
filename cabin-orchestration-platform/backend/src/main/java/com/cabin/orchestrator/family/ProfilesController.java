package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profiles")
@CrossOrigin
public class ProfilesController {

    private final FamilyProfileService profiles;

    public ProfilesController(FamilyProfileService profiles) { this.profiles = profiles; }

    @GetMapping
    public List<FamilyProfile> list() {
        return profiles.list();
    }

    @PostMapping
    public FamilyProfile create(@RequestBody Map<String, Object> body) {
        FamilyProfile p = fromBody(requireId(body), body);
        if (p.name() == null || p.name().isBlank() || p.role() == null || p.role().isBlank()) {
            throw new IllegalArgumentException("name and role are required");
        }
        return profiles.create(p);
    }

    @PatchMapping("/{id}")
    public FamilyProfile update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        FamilyProfile patch = fromBody(id, body);
        FamilyProfile updated = profiles.update(id, patch);
        if (updated == null) throw new IllegalArgumentException("No profile with id " + id);
        return updated;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        if (!profiles.archive(id)) throw new IllegalArgumentException("No profile with id " + id);
    }

    private String requireId(Map<String, Object> body) {
        Object id = body.get("id");
        if (id == null || id.toString().isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return id.toString();
    }

    private FamilyProfile fromBody(String id, Map<String, Object> body) {
        return new FamilyProfile(
            id,
            (String) body.get("name"),
            (String) body.get("role"),
            (String) body.get("birthday"),
            (String) body.get("avatar"),
            (String) body.get("color"),
            body.get("age") == null ? null : ((Number) body.get("age")).intValue(),
            (String) body.get("type"),
            (String) body.get("relation"),
            0, true, 0, 0
        );
    }
}
