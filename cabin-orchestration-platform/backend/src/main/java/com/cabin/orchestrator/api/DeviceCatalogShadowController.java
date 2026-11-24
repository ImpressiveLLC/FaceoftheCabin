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
        Map<String, Integer> ruleCounts = new TreeMap<>(Map.of("evaluated", 0, "notEvaluated", 0, "unavailable", 0,
            "independent", 0, "delegated", 0, "workaroundMatches", 0));
        Map<String, List<String>> ruleDisagreements = new TreeMap<>();
        int devices = 0;
        for (DeviceStatus status : registry.all()) {
            devices++;
            var observed = catalog.observation(status.deviceId());
            if (observed.isEmpty()) {
                ruleCounts.merge("notEvaluated", 1, Integer::sum);
            } else if (!DeviceCatalogService.RuleObservation.EVALUATED.equals(observed.get().status())) {
                ruleCounts.merge("unavailable", 1, Integer::sum);
            } else {
                var o = observed.get();
                ruleCounts.merge("evaluated", 1, Integer::sum);
                ruleCounts.merge(o.independent() ? "independent" : "delegated", 1, Integer::sum);
                if (o.workaround()) ruleCounts.merge("workaroundMatches", 1, Integer::sum);
                if (o.ruleType() != o.inferredType()) {
                    ruleDisagreements.computeIfAbsent("RULE_INFERENCE_DIFFERS", k -> new ArrayList<>()).add(status.deviceId());
                }
                if (o.ruleType() != status.type()) {
                    ruleDisagreements.computeIfAbsent("ASSIGNED_TYPE_DIFFERS", k -> new ArrayList<>()).add(status.deviceId());
                }
            }
            DeviceCatalogService.ShadowMatch match = catalog.shadow(status.type());
            if (match.disagreement() != null) {
                disagreements.computeIfAbsent(match.disagreement().name(), k -> new ArrayList<>()).add(status.deviceId());
            } else {
                byEntry.merge(match.ontologyTypeId(), 1, Integer::sum);
            }
        }
        disagreements.values().forEach(ids -> ids.sort(null));
        ruleDisagreements.values().forEach(ids -> ids.sort(null));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("catalogSource", catalog.source());
        out.put("catalogHash", catalog.catalogHash().orElse(null));
        out.put("catalogEntries", catalog.entryCount());
        out.put("matchedBy", DeviceCatalogService.MATCHED_BY_ASSIGNED_TYPE);
        out.put("devices", devices);
        out.put("matched", devices - disagreements.values().stream().mapToInt(List::size).sum());
        out.put("matchedByEntry", byEntry);
        out.put("disagreements", disagreements);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("evaluator", catalog.evaluatorProblem() == null ? "available" : "unavailable: " + catalog.evaluatorProblem());
        rules.put("counts", ruleCounts);
        rules.put("ruleInferenceDiffers", ruleDisagreements.getOrDefault("RULE_INFERENCE_DIFFERS", List.of()).size());
        rules.put("assignedTypeDiffers", ruleDisagreements.getOrDefault("ASSIGNED_TYPE_DIFFERS", List.of()).size());
        rules.put("disagreements", ruleDisagreements);
        rules.put("scope", "S3b rule shadow: the catalog's match rules evaluated on each device's last classifier input, "
            + "compared with inferType on that identical input (inference parity) and, separately, with the persisted "
            + "assigned type (drift, an observation only). A device whose source has not re-announced since startup is "
            + "NOT_EVALUATED. This runtime observation does not close parity check 7 (W-170).");
        out.put("ruleShadow", rules);
        out.put("scope", "Shadow read only: no assigned type, capability or telemetry changes. matchedBy is the assigned "
            + "type; ruleShadow is the independent evaluation of the catalog's own rules.");
        return out;
    }
}
