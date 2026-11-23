package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.ontology.DeviceCatalogService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Catalog S3 (W-161): the disagreement report for the shadow read, computed fresh over every device the registry
 * holds. Read-only and observation only. Signed-in callers only (under /api/devices/**); demo links are denied
 * (DemoAccessPolicy), because the report lists device ids.
 */
@RestController
@RequestMapping("/api/devices")
@CrossOrigin
public class DeviceCatalogShadowController {

    private final DeviceRegistry registry;
    private final DeviceCatalogService catalog;

    public DeviceCatalogShadowController(DeviceRegistry registry, DeviceCatalogService catalog) {
        this.registry = registry;
        this.catalog = catalog;
    }

    /** GET /api/devices/catalog-shadow */
    @GetMapping("/catalog-shadow")
    public Map<String, Object> report() {
        Map<String, Integer> byEntry = new TreeMap<>();
        Map<String, List<String>> disagreements = new TreeMap<>();
        int devices = 0;
        for (DeviceStatus status : registry.all()) {
            devices++;
            DeviceCatalogService.ShadowMatch match = catalog.shadow(status.type());
            if (match.disagreement() != null) {
                disagreements.computeIfAbsent(match.disagreement().name(), k -> new ArrayList<>()).add(status.deviceId());
            } else {
                byEntry.merge(match.ontologyTypeId(), 1, Integer::sum);
            }
        }
        disagreements.values().forEach(ids -> ids.sort(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalogSource", catalog.source());
        out.put("catalogHash", catalog.catalogHash().orElse(null));
        out.put("catalogEntries", catalog.entryCount());
        out.put("matchedBy", DeviceCatalogService.MATCHED_BY_ASSIGNED_TYPE);
        out.put("devices", devices);
        out.put("matched", devices - disagreements.values().stream().mapToInt(List::size).sum());
        out.put("matchedByEntry", byEntry);
        out.put("disagreements", disagreements);
        out.put("scope", "Shadow read only: no assigned type, capability or telemetry changes. The catalog entry is found "
            + "from the type inferType assigned; the catalog's match rules are not evaluated independently in this slice.");
        return out;
    }
}
