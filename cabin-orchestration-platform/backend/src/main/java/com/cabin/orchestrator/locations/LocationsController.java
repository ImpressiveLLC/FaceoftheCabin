package com.cabin.orchestrator.locations;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Same open/ungated posture as DeviceController (not ProfilesController) --
 * see security.WebConfig's comment for the actual gated-path list. This
 * exposes hub connection URLs (Grafana/Node-RED/HA/etc.), not personal
 * family data, and the real security boundary for those admin surfaces is
 * Tailscale, not this API (REPLICATION.md §7).
 */
@RestController
@RequestMapping("/api/locations")
@CrossOrigin
public class LocationsController {

    private final HubLocationService locations;

    public LocationsController(HubLocationService locations) { this.locations = locations; }

    @GetMapping
    public List<HubLocation> list() {
        return locations.list();
    }

    @PostMapping
    public HubLocation create(@RequestBody Map<String, Object> body) {
        HubLocation loc = fromBody(requireId(body), body);
        if (loc.label() == null || loc.label().isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        return locations.create(loc);
    }

    @PatchMapping("/{id}")
    public HubLocation update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        HubLocation patch = fromBody(id, body);
        HubLocation updated = locations.update(id, patch);
        if (updated == null) throw new IllegalArgumentException("No location with id " + id);
        return updated;
    }

    @PostMapping("/reorder")
    public void reorder(@RequestBody List<String> orderedIds) {
        locations.reorder(orderedIds);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        if (!locations.archive(id)) throw new IllegalArgumentException("No location with id " + id);
    }

    private String requireId(Map<String, Object> body) {
        Object id = body.get("id");
        if (id == null || id.toString().isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return id.toString();
    }

    private HubLocation fromBody(String id, Map<String, Object> body) {
        return new HubLocation(
            id,
            (String) body.get("label"),
            (String) body.get("apiBase"),
            (String) body.get("wsBase"),
            (String) body.get("grafanaUrl"),
            (String) body.get("noderedUrl"),
            (String) body.get("haUrl"),
            (String) body.get("frigateUrl"),
            (String) body.get("z2mUrl"),
            (String) body.get("familyHubUrl"),
            0, true, 0, 0
        );
    }
}
