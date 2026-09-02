package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.JdbcDeviceLifecycleVocabularyStore;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfig;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfigService;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/devices")
@CrossOrigin
public class DeviceController {

    private final DeviceRegistry registry;
    private final Zigbee2MqttAdapter z2mAdapter;
    private final DeviceHealthMonitor healthMonitor;
    private final DeviceDisplayConfigService displayConfigService;
    private final JdbcDeviceLifecycleVocabularyStore lifecycleVocabulary;

    public DeviceController(DeviceRegistry registry,
                             Zigbee2MqttAdapter z2mAdapter,
                             DeviceHealthMonitor healthMonitor,
                             DeviceDisplayConfigService displayConfigService,
                             JdbcDeviceLifecycleVocabularyStore lifecycleVocabulary) {
        this.registry = registry;
        this.z2mAdapter = z2mAdapter;
        this.healthMonitor = healthMonitor;
        this.displayConfigService = displayConfigService;
        this.lifecycleVocabulary = lifecycleVocabulary;
    }

    /** List devices worth showing on monitoring surfaces (everything except deferred/ignored). */
    @GetMapping
    public List<DeviceStatus> listDevices() {
        return registry.visible();
    }

    /** Passively discovered devices awaiting an explicit person-authored decision. */
    @GetMapping("/candidates")
    public List<DeviceStatus> listCandidates() {
        return registry.candidates();
    }

    /**
     * Cached metadata for deferred/ignored devices. Reading this list never
     * invokes an adapter fetch or sends a command to a device.
     */
    @GetMapping("/previously-exposed")
    public List<DeviceStatus> listPreviouslyExposed() {
        return registry.previouslyExposed();
    }

    /** Get single device */
    @GetMapping("/{deviceId}")
    public DeviceStatus getDevice(@PathVariable String deviceId) {
        return registry.get(deviceId);
    }

    /**
     * Per-device checkin status (ON_SCHEDULE / LATE / MISSED / NOT_CONFIGURED) —
     * the "is it actually broken or just hasn't reported yet" signal, distinct
     * from DeviceStatus.state. See DeviceHealthMonitor's class comment.
     */
    @GetMapping("/checkin-status")
    public Map<String, String> checkinStatus() {
        Map<String, String> out = new LinkedHashMap<>();
        healthMonitor.getCheckinStatuses().forEach((id, status) -> out.put(id, status.name()));
        return out;
    }

    @GetMapping("/checkin-details")
    public Map<String, Map<String, Object>> checkinDetails() {
        return healthMonitor.getCheckinDetails();
    }

    /** Register a new device (Device Manager UI → add device) */
    @PostMapping
    public DeviceDescriptor registerDevice(@RequestBody DeviceDescriptor descriptor) {
        return registry.registerConfiguredDevice(descriptor);
    }

    /** Update device config */
    @PutMapping("/{deviceId}")
    public DeviceDescriptor updateDevice(@PathVariable String deviceId,
                                          @RequestBody DeviceDescriptor descriptor) {
        if (!deviceId.equals(descriptor.deviceId())) {
            throw new IllegalArgumentException("Path and descriptor device IDs must match");
        }
        return registry.registerConfiguredDevice(descriptor);
    }

    /** Remove device */
    @DeleteMapping("/{deviceId}")
    public Map<String, String> removeDevice(@PathVariable String deviceId) {
        registry.remove(deviceId);
        return Map.of("removed", deviceId);
    }

    /** Send a command to a device */
    @PostMapping("/{deviceId}/command")
    public Map<String, Object> sendCommand(@PathVariable String deviceId,
                                            @RequestBody Map<String, Object> body) {
        String command = String.valueOf(body.get("command"));
        Object payload = body.get("payload");
        boolean ok = registry.sendCommand(deviceId, command, payload);
        return Map.of("deviceId", deviceId, "command", command, "accepted", ok);
    }

    /** List available device types and capabilities (Device Manager discovery) */
    @GetMapping("/meta/types")
    public Map<String, Object> deviceMeta() {
        return Map.of(
            "types", DeviceType.values(),
            "capabilities", DeviceCapability.values(),
            "adapters", List.of("mqtt", "ha_rest", "rtsp", "http_poll", "google_sdm")
        );
    }

    /** The one-stop, queryable source of truth for valid device lifecycle states/actions -- see JdbcDeviceLifecycleVocabularyStore's own doc. */
    @GetMapping("/meta/lifecycle")
    public Map<String, Object> lifecycleMeta() {
        return Map.of(
            "states", lifecycleVocabulary.loadStates(),
            "actions", lifecycleVocabulary.loadActions()
        );
    }

    /**
     * Open or close the Zigbee pairing window.
     * Body: { "enable": true, "duration": 254 }
     * Duration is in seconds; 254 = max (~4m14s).
     */
    @PostMapping("/permit-join")
    public Map<String, Object> permitJoin(@RequestBody Map<String, Object> body) {
        boolean enable = Boolean.TRUE.equals(body.get("enable"));
        int duration = body.containsKey("duration") ? ((Number) body.get("duration")).intValue() : 254;
        z2mAdapter.permitJoin(enable, duration);
        return Map.of("permitJoin", enable, "duration", duration);
    }

    /**
     * Get or update per-device config (DeviceDescriptor fields writable by the UI).
     * PATCH accepts a partial map: 'name' and 'enabled' (DeviceDescriptor
     * fields), plus 'room' (added 2026-08-18 -- a durable per-device fact
     * that rides DeviceLifecycleRecord.extraAttributes instead, see its own
     * comment for why). Room isn't in getDeviceConfig's response below
     * because it's runtime DeviceStatus.attributes, not descriptor state --
     * read it from GET /api/devices/{id} instead, same as any other
     * discovered/derived attribute.
     */
    @GetMapping("/{deviceId}/config")
    public Map<String, Object> getDeviceConfig(@PathVariable String deviceId) {
        return registry.descriptor(deviceId)
            .map(d -> Map.<String, Object>of(
                "deviceId", d.deviceId(),
                "name", d.name(),
                "type", d.type(),
                "capabilities", d.capabilities(),
                "protocolAdapter", d.protocolAdapter(),
                "connectionString", d.connectionString(),
                "enabled", d.enabled(),
                "location", d.location(),
                "deviceLifecycle", registry.lifecycleState(deviceId)
            ))
            .orElse(Map.of("error", "not found"));
    }

    @PatchMapping("/{deviceId}/config")
    public Map<String, Object> patchDeviceConfig(@PathVariable String deviceId,
                                                  @RequestBody Map<String, Object> patch) {
        return registry.descriptor(deviceId).map(existing -> {
            String name    = patch.containsKey("name") ? (String) patch.get("name") : existing.name();
            boolean enabled = patch.containsKey("enabled")
                ? Boolean.TRUE.equals(patch.get("enabled")) : existing.enabled();
            Map<String, Object> extraAttributes = new java.util.LinkedHashMap<>();
            if (patch.containsKey("room")) {
                Object room = patch.get("room");
                // Blank clears it (stored as "" rather than removed -- the
                // JSONB shallow-merge write path has no delete semantics,
                // only overwrite; an empty string reads the same as
                // "no room" everywhere the UI checks it).
                extraAttributes.put("room", room == null ? "" : String.valueOf(room).trim());
            }
            if (patch.containsKey("parentDeviceId")) {
                // Same blank-clears-it convention as room above. Real
                // validation (exists, same location, no self/cycle) lives
                // in DeviceRegistry.saveConfiguration() -- an invalid
                // value throws IllegalArgumentException, caught below.
                Object parentDeviceId = patch.get("parentDeviceId");
                extraAttributes.put("parentDeviceId", parentDeviceId == null ? "" : String.valueOf(parentDeviceId).trim());
            }
            DeviceRegistry.ConfigurationSaveResult result;
            try {
                result = registry.saveConfiguration(deviceId, name, enabled, extraAttributes);
            } catch (IllegalArgumentException e) {
                return Map.<String, Object>of("error", e.getMessage());
            }
            healthMonitor.refreshAfterConfigurationChange(deviceId);
            return Map.<String, Object>of(
                "updated", deviceId,
                "name", result.descriptor().name(),
                "enabled", result.descriptor().enabled(),
                "changed", result.changed(),
                "deviceLifecycle", result.lifecycleState());
        }).orElse(Map.of("error", "not found"));
    }

    /** Persist an explicit review decision; merely opening/closing review is a no-op. */
    @PostMapping("/{deviceId}/lifecycle")
    public Map<String, Object> updateLifecycle(@PathVariable String deviceId,
                                               @RequestBody Map<String, Object> body) {
        DeviceLifecycleAction action = DeviceLifecycleAction.from(
            body.get("action") == null ? null : String.valueOf(body.get("action")));
        DeviceRegistry.LifecycleChangeResult result = registry.applyLifecycleAction(deviceId, action);
        healthMonitor.refreshAfterConfigurationChange(deviceId);
        return Map.of(
            "deviceId", deviceId,
            "changed", result.changed(),
            "deviceLifecycle", result.lifecycleState());
    }

    // ── Display-config endpoints ───────────────────────────────────────────────

    /**
     * Bulk: all display configs for an active presence profile.
     * GET /api/devices/display-config?profile=AT_HOME
     */
    @GetMapping("/display-config")
    public List<DeviceDisplayConfig> bulkDisplayConfig(@RequestParam String profile) {
        return displayConfigService.allForProfile(profile);
    }

    /**
     * Single device, single profile.
     * GET /api/devices/{id}/display-config?profile=AT_HOME
     */
    @GetMapping("/{deviceId}/display-config")
    public Map<String, Object> getDisplayConfig(@PathVariable String deviceId,
                                                 @RequestParam String profile) {
        String location = registry.descriptor(deviceId).map(DeviceDescriptor::location).orElse("cabin");
        return displayConfigService.get(deviceId, location, profile)
            .map(c -> Map.<String, Object>of(
                "deviceId",        c.deviceId(),
                "location",        c.location(),
                "presenceProfile", c.presenceProfile(),
                "displayName",     c.displayName() != null ? c.displayName() : "",
                "stateLabelMap",   c.stateLabelMap(),
                "severityOverride", c.severityOverride() != null ? c.severityOverride() : ""))
            .orElse(Map.of("deviceId", deviceId, "presenceProfile", profile,
                           "displayName", "", "stateLabelMap", Map.of(), "severityOverride", ""));
    }

    /**
     * Upsert display config.
     * PATCH /api/devices/{id}/display-config?profile=AT_HOME
     * Body: { displayName, stateLabelMap, severityOverride }
     */
    @PatchMapping("/{deviceId}/display-config")
    @SuppressWarnings("unchecked")
    public DeviceDisplayConfig patchDisplayConfig(@PathVariable String deviceId,
                                                   @RequestParam String profile,
                                                   @RequestBody Map<String, Object> body) {
        String location = registry.descriptor(deviceId).map(DeviceDescriptor::location).orElse("cabin");
        String displayName     = (String) body.getOrDefault("displayName", null);
        Object labelRaw        = body.get("stateLabelMap");
        Map<String, String> labelMap = (labelRaw instanceof Map) ? (Map<String, String>) labelRaw : Map.of();
        String severityOverride = (String) body.getOrDefault("severityOverride", null);
        if (severityOverride != null && severityOverride.isBlank()) severityOverride = null;
        if (displayName      != null && displayName.isBlank())      displayName = null;
        return displayConfigService.upsert(
            new DeviceDisplayConfig(deviceId, location, profile, displayName, labelMap, severityOverride));
    }

    /**
     * Delete display config for one device+profile combination.
     * DELETE /api/devices/{id}/display-config?profile=AT_HOME
     */
    @DeleteMapping("/{deviceId}/display-config")
    public Map<String, String> deleteDisplayConfig(@PathVariable String deviceId,
                                                    @RequestParam String profile) {
        String location = registry.descriptor(deviceId).map(DeviceDescriptor::location).orElse("cabin");
        displayConfigService.delete(deviceId, location, profile);
        return Map.of("deleted", deviceId, "profile", profile);
    }
}
