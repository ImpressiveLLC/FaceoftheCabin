package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.JdbcDeviceDiscoveryStore;
import com.cabin.orchestrator.devices.model.*;
import com.cabin.orchestrator.integrations.discovery.DiscoveryServiceClient;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Self-discovery / assisted-onboarding endpoints. A discovery run never
 * touches the live device registry by itself -- it only produces a
 * proposal (JdbcDeviceDiscoveryStore), persisted separately from the
 * `device` table. Nothing here changes a real device until an explicit
 * .../discovery/apply call, driven by a person selecting fields in the
 * review overlay. See docs/ontology.yaml's device_discovery_result entity.
 */
@RestController
@RequestMapping("/api/devices")
@CrossOrigin
public class DeviceDiscoveryController {

    private final DeviceRegistry registry;
    private final JdbcDeviceDiscoveryStore discoveryStore;
    private final DiscoveryServiceClient discoveryClient;
    // Small fixed pool: discovery runs are person-paced (one review at a
    // time in practice), not a high-throughput path -- no need for a queue.
    private final Executor discoveryExecutor = Executors.newFixedThreadPool(2);

    public DeviceDiscoveryController(DeviceRegistry registry, JdbcDeviceDiscoveryStore discoveryStore,
                                      DiscoveryServiceClient discoveryClient) {
        this.registry = registry;
        this.discoveryStore = discoveryStore;
        this.discoveryClient = discoveryClient;
    }

    /** Kick off a self-discovery lookup. Returns immediately with a runId; poll .../discovery/latest. */
    @PostMapping("/{deviceId}/discovery/run")
    public Map<String, Object> runDiscovery(@PathVariable String deviceId) {
        DeviceStatus status = registry.get(deviceId);
        Optional<DeviceDescriptor> descriptor = registry.descriptor(deviceId);
        if (status == null || descriptor.isEmpty()) {
            return Map.of("error", "not found");
        }
        String runId = UUID.randomUUID().toString();
        Instant requestedAt = Instant.now();
        // A pending row (empty matches, no appliedAt) lets the frontend
        // distinguish "still running" from "no discovery has ever been run
        // for this device" while polling latest().
        discoveryStore.save(new DeviceDiscoveryResult(runId, deviceId, requestedAt, null, List.of()));

        CompletableFuture.runAsync(() -> {
            DeviceDiscoveryResult result = discoveryClient.runDiscovery(runId, descriptor.get(), status.attributes());
            discoveryStore.save(result);
        }, discoveryExecutor);

        return Map.of("runId", runId, "deviceId", deviceId, "requestedAt", requestedAt.toString());
    }

    /** Most recent discovery result for a device. pending=true while a run is still in flight. */
    @GetMapping("/{deviceId}/discovery/latest")
    public Map<String, Object> latestDiscovery(@PathVariable String deviceId) {
        return discoveryStore.latestFor(deviceId)
            .<Map<String, Object>>map(result -> {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("runId", result.runId());
                out.put("deviceId", result.deviceId());
                out.put("requestedAt", result.requestedAt().toString());
                out.put("appliedAt", result.appliedAt() == null ? null : result.appliedAt().toString());
                out.put("pending", result.matches().isEmpty() && result.appliedAt() == null);
                out.put("matches", result.matches());
                return out;
            })
            .orElseGet(() -> Map.of("pending", false, "matches", List.of()));
    }

    /**
     * Apply person-selected fields from a discovery result.
     * mode=new     -- accept a CANDIDATE and configure it in one step
     *                 (applyLifecycleAction(ACCEPT) then saveConfiguration()
     *                 -- the exact same two calls the manual review flow
     *                 makes, just pre-filled from the discovery result).
     * mode=replace -- "replace device settings with new definitions" for an
     *                 already-configured device's re-sync
     *                 (DeviceRegistry.replaceConfiguration()).
     * Neither ever runs without this explicit call.
     */
    @PostMapping("/{deviceId}/discovery/apply")
    public Map<String, Object> applyDiscovery(@PathVariable String deviceId, @RequestBody Map<String, Object> body) {
        Object runId = body.get("runId");
        String mode = String.valueOf(body.get("mode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) body.getOrDefault("fields", Map.of());

        Optional<DeviceDescriptor> existingOpt = registry.descriptor(deviceId);
        if (existingOpt.isEmpty()) return Map.of("error", "not found");
        DeviceDescriptor existing = existingOpt.get();

        try {
            DeviceRegistry.ConfigurationSaveResult saved = switch (mode) {
                case "new" -> applyNew(deviceId, existing, fields);
                case "replace" -> applyReplace(deviceId, existing, fields);
                default -> throw new IllegalArgumentException("mode must be 'new' or 'replace'");
            };
            if (runId != null) discoveryStore.markApplied(String.valueOf(runId), Instant.now());
            return Map.of("deviceId", deviceId, "changed", saved.changed(), "deviceLifecycle", saved.lifecycleState());
        } catch (IllegalStateException | IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }
    }

    private DeviceRegistry.ConfigurationSaveResult applyNew(String deviceId, DeviceDescriptor existing,
                                                              Map<String, Object> fields) {
        DeviceLifecycleState current = registry.lifecycleState(deviceId);
        if (current == DeviceLifecycleState.ASSIGNED) {
            throw new IllegalStateException("Device is already assigned -- use mode=replace to update its configuration");
        }
        if (current == DeviceLifecycleState.CANDIDATE) {
            registry.applyLifecycleAction(deviceId, DeviceLifecycleAction.ACCEPT);
        }
        String name = fields.containsKey("name") ? String.valueOf(fields.get("name")) : existing.name();
        boolean enabled = Boolean.TRUE.equals(fields.get("enabled"));
        return registry.saveConfiguration(deviceId, name, enabled);
    }

    private DeviceRegistry.ConfigurationSaveResult applyReplace(String deviceId, DeviceDescriptor existing,
                                                                  Map<String, Object> fields) {
        DeviceDescriptor proposed = new DeviceDescriptor(
            existing.deviceId(),
            fields.containsKey("name") ? String.valueOf(fields.get("name")) : existing.name(),
            fields.containsKey("type") ? DeviceType.valueOf(String.valueOf(fields.get("type"))) : existing.type(),
            fields.containsKey("capabilities") ? parseCapabilities(fields.get("capabilities")) : existing.capabilities(),
            existing.protocolAdapter(), existing.connectionString(), existing.enabled(),
            fields.containsKey("location") ? String.valueOf(fields.get("location")) : existing.location());
        return registry.replaceConfiguration(deviceId, proposed, fields.keySet());
    }

    private Set<DeviceCapability> parseCapabilities(Object raw) {
        if (!(raw instanceof List<?> list)) return Set.of();
        EnumSet<DeviceCapability> caps = EnumSet.noneOf(DeviceCapability.class);
        for (Object item : list) {
            try {
                caps.add(DeviceCapability.valueOf(String.valueOf(item)));
            } catch (IllegalArgumentException ignored) {
                // An unrecognized capability name from the discovery service
                // is silently dropped rather than failing the whole apply --
                // matches DeviceRegistry's own tolerance elsewhere (e.g.
                // JdbcDeviceLifecycleStore.capabilities()).
            }
        }
        return caps;
    }
}
