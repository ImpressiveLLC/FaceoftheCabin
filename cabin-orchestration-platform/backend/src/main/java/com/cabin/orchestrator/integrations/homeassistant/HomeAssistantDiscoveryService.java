package com.cabin.orchestrator.integrations.homeassistant;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.*;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Turns recognized Home Assistant entities into visible candidates and consumes
 * HA person/device-tracker/occupancy entities as presence signals. Discovery is
 * intentionally non-destructive: candidates cannot receive commands until a
 * user enables them in Device Manager.
 */
@Service
public class HomeAssistantDiscoveryService {
    // number/select added 2026-08-18: found live that the Liebherr fridge's
    // two setpoint entities (number.*) and its icemaker mode (select.*)
    // were silently skipped by this allowlist every discovery cycle,
    // forever -- no error, no log line, they just never became candidates.
    // 6 of the fridge's 9 real entities were already discoverable
    // (sensor/switch); this closes the remaining gap rather than leaving
    // a third of one real device permanently invisible regardless of what
    // a user does in Device Manager.
    private static final Set<String> DEVICE_DOMAINS = Set.of(
        "binary_sensor", "sensor", "lock", "climate", "switch", "light", "cover", "camera",
        "number", "select");

    private final HomeAssistantAdapter adapter;
    private final DeviceRegistry registry;
    private final PresenceSignalRegistry presenceSignals;
    private final PresenceService presenceService;

    public HomeAssistantDiscoveryService(HomeAssistantAdapter adapter, DeviceRegistry registry,
            PresenceSignalRegistry presenceSignals, PresenceService presenceService) {
        this.adapter = adapter;
        this.registry = registry;
        this.presenceSignals = presenceSignals;
        this.presenceService = presenceService;
    }

    @Scheduled(fixedDelayString = "${cabin.homeassistant.discovery-interval-ms:60000}")
    public void discover() {
        discoverLocation("cabin");
        discoverLocation("home");
        presenceService.recomputeFromSignals();
    }

    void discoverLocation(String location) {
        // One bulk lookup per cycle, not one per entity -- see
        // HomeAssistantAdapter.deviceIdsByEntity's own javadoc for what
        // this is and why it fails safe to an empty map (blank token,
        // unreachable HA, template error). Composite grouping is then
        // just "these entities share the same haDeviceId attribute" --
        // no device_id resolved for an entity (helpers/templates
        // genuinely have none, or the lookup failed entirely) simply
        // means it stays ungrouped, exactly like every entity did before
        // this existed. Real device/UI grouping into a single expandable
        // card lives client-side (Device Manager) -- this only supplies
        // the raw fact.
        Map<String, String> deviceIds = adapter.deviceIdsByEntity(location);
        for (HomeAssistantAdapter.DiscoveredEntity entity : adapter.discover(location)) {
            String domain = domain(entity.entityId());
            if (isPresence(entity, domain)) {
                presenceSignals.record(location, entity.entityId(), isPresent(entity.state()));
            }
            if (!DEVICE_DOMAINS.contains(domain)) continue;

            String generatedId = "ha-" + location + "-" + entity.entityId().replace('.', '-').replace('_', '-');
            String id = registry.descriptorByConnection("ha_rest", entity.entityId(), location)
                .map(DeviceDescriptor::deviceId).orElse(generatedId);
            DeviceType type = inferType(domain, entity.attributes());
            Set<DeviceCapability> capabilities = inferCapabilities(domain, type);
            String name = String.valueOf(entity.attributes().getOrDefault("friendly_name", entity.entityId()));
            DeviceDescriptor descriptor = new DeviceDescriptor(id, name, type, capabilities,
                "ha_rest", entity.entityId(), false, location);
            Map<String, Object> attrs = new LinkedHashMap<>(entity.attributes());
            attrs.put("entityId", entity.entityId());
            attrs.put("discoveredFrom", "Home Assistant");
            String haDeviceId = deviceIds.get(entity.entityId());
            if (haDeviceId != null && !haDeviceId.isBlank()) {
                attrs.put("haDeviceId", haDeviceId);
            }
            if (attrs.containsKey("battery_level") && !attrs.containsKey("battery")) {
                attrs.put("battery", attrs.get("battery_level"));
            }
            registry.registerCandidate(descriptor, attrs);
            DeviceStatus current = registry.get(id);
            if (current == null) continue; // device was removed concurrently
            DeviceDescriptor registered = registry.descriptor(id).orElse(descriptor);
            Map<String, Object> merged = new LinkedHashMap<>(current.attributes());
            merged.putAll(attrs);
            boolean safetyAlarm = Set.of(DeviceType.SMOKE_ALARM, DeviceType.CO_ALARM,
                DeviceType.WATER_LEAK_SENSOR).contains(registered.type()) && isPresent(entity.state());
            registry.update(new DeviceStatus(id, registered.type(), registered.name(),
                safetyAlarm ? "ALARM" : adapter.normalizedState(entity.state()),
                Instant.now(), merged, registered.location()));
        }
    }

    private String domain(String entityId) {
        int dot = entityId.indexOf('.');
        return dot > 0 ? entityId.substring(0, dot) : "";
    }

    private boolean isPresence(HomeAssistantAdapter.DiscoveredEntity entity, String domain) {
        String deviceClass = String.valueOf(entity.attributes().getOrDefault("device_class", ""));
        return "person".equals(domain) || "device_tracker".equals(domain)
            || "presence".equals(deviceClass) || "occupancy".equals(deviceClass);
    }

    private boolean isPresent(String state) {
        return Set.of("home", "on", "present", "occupied").contains(state.toLowerCase(Locale.ROOT));
    }

    private DeviceType inferType(String domain, Map<String, Object> attrs) {
        String deviceClass = String.valueOf(attrs.getOrDefault("device_class", "")).toLowerCase(Locale.ROOT);
        if ("lock".equals(domain)) return DeviceType.LOCK;
        if ("climate".equals(domain)) return DeviceType.THERMOSTAT;
        if ("camera".equals(domain)) return DeviceType.CAMERA;
        if (deviceClass.contains("smoke")) return DeviceType.SMOKE_ALARM;
        if (deviceClass.contains("carbon_monoxide") || deviceClass.equals("co")) return DeviceType.CO_ALARM;
        if (deviceClass.contains("moisture")) return DeviceType.WATER_LEAK_SENSOR;
        if (deviceClass.contains("motion") || deviceClass.contains("occupancy")) return DeviceType.MOTION_SENSOR;
        if (deviceClass.contains("door") || deviceClass.contains("opening")) return DeviceType.CONTACT_SENSOR;
        if (deviceClass.contains("temperature")) return DeviceType.TEMPERATURE_SENSOR;
        if (deviceClass.contains("humidity")) return DeviceType.HUMIDITY_SENSOR;
        if (deviceClass.contains("power") || deviceClass.contains("energy")) return DeviceType.POWER_METER;
        return DeviceType.HOME_ASSISTANT_ENTITY;
    }

    private Set<DeviceCapability> inferCapabilities(String domain, DeviceType type) {
        Set<DeviceCapability> caps = new HashSet<>();
        caps.add(DeviceCapability.TELEMETRY);
        if (Set.of("lock", "climate", "switch", "light", "cover").contains(domain)) caps.add(DeviceCapability.COMMAND);
        if (type == DeviceType.LOCK) caps.add(DeviceCapability.ACCESS_CONTROL);
        if (type == DeviceType.THERMOSTAT) caps.add(DeviceCapability.CLIMATE);
        if (type == DeviceType.CAMERA) caps.add(DeviceCapability.STREAM);
        if (Set.of(DeviceType.SMOKE_ALARM, DeviceType.CO_ALARM, DeviceType.WATER_LEAK_SENSOR).contains(type)) caps.add(DeviceCapability.ALARM);
        if (type == DeviceType.MOTION_SENSOR) caps.add(DeviceCapability.PRESENCE);
        return caps;
    }
}
