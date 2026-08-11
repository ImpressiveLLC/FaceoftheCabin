package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all devices across all locations.
 * - DeviceDescriptor: static config (capabilities, adapter, connection, location)
 * - DeviceStatus: runtime state (updated by MQTT bridge and HA polling)
 * Dispatches commands to the correct ProtocolAdapter.
 */
@Component
public class DeviceRegistry {

    private final Map<String, DeviceStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, DeviceDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, ProtocolAdapter> adapters = new ConcurrentHashMap<>();

    public DeviceRegistry(List<ProtocolAdapter> adapterList) {
        adapterList.forEach(a -> adapters.put(a.adapterType(), a));
        seedDefaults();
    }

    private void seedDefaults() {
        // ── Cabin devices — real paired hardware as of 2026-07-25 ────────────
        // Zigbee devices auto-registered by Zigbee2MqttAdapter (z2m- prefix).
        // Seeds here are non-Zigbee cabin devices only.

        // Future: add cabin thermostat, smoke alarm, cameras when installed
        // Future: add home hub devices when home-hub is deployed

        // ── Home devices — disabled until home-hub deployed ──────────────────

        // Reolink RLC-810A PoE cameras (5× — Frigate, home LAN 192.168.1.20–24)
        registerDescriptor(new DeviceDescriptor(
            "home-cam-front", "Home Front Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.20:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-driveway", "Home Driveway Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.21:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-backyard", "Home Backyard Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.22:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-garage", "Home Garage Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.23:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-side", "Home Side Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.24:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lock-front", "Home Front Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_front_door", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lock-back", "Home Back Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_back_door", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-thermostat-main", "Home Thermostat", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_thermostat", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-smoke-co-main", "Home Smoke/CO Alarm", DeviceType.SMOKE_ALARM,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "ha_rest", "binary_sensor.home_kidde_smoke_co", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-energy-main", "Home Energy Monitor", DeviceType.POWER_METER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.POWER_MONITOR),
            "ha_rest", "sensor.home_emporia_total_power_w", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lg-washer", "Home LG Washer", DeviceType.WASHING_MACHINE,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_washer_state", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lg-dryer", "Home LG Dryer", DeviceType.DRYER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_dryer_state", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-bosch-dishwasher", "Home Bosch Dishwasher", DeviceType.DISHWASHER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_bosch_dishwasher_door", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-daikin-hvac", "Home Daikin Aurora HVAC", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_daikin_aurora", false, "home"));
    }

    public void registerDescriptor(DeviceDescriptor desc) {
        descriptors.put(desc.deviceId(), desc);
        if (!statuses.containsKey(desc.deviceId())) {
            statuses.put(desc.deviceId(), new DeviceStatus(
                desc.deviceId(), desc.type(), desc.name(), "UNKNOWN",
                Instant.now(), Map.of(), desc.location()));
        }
    }

    /**
     * Register a device seen by an integration without pretending a person has
     * configured it. Discovery metadata travels with the status so every UI can
     * render the candidate and explain where it came from.
     */
    public void registerCandidate(DeviceDescriptor desc, Map<String, Object> discoveryAttributes) {
        descriptors.putIfAbsent(desc.deviceId(), desc);
        statuses.compute(desc.deviceId(), (id, existing) -> {
            Map<String, Object> attrs = new LinkedHashMap<>(existing == null ? Map.of() : existing.attributes());
            if (discoveryAttributes != null) attrs.putAll(discoveryAttributes);
            attrs.putIfAbsent("candidate", !desc.enabled());
            attrs.put("source", desc.protocolAdapter());
            attrs.put("capabilities", desc.capabilities().stream().map(Enum::name).sorted().toList());
            return new DeviceStatus(id, desc.type(), desc.name(), existing == null ? "UNKNOWN" : existing.state(),
                existing == null ? Instant.now() : existing.lastSeen(), attrs, desc.location());
        });
    }

    public Optional<DeviceDescriptor> descriptorByConnection(String adapter, String connection, String location) {
        return descriptors.values().stream()
            .filter(d -> Objects.equals(adapter, d.protocolAdapter()))
            .filter(d -> Objects.equals(connection, d.connectionString()))
            .filter(d -> Objects.equals(location, d.location()))
            .findFirst();
    }

    public void register(DeviceStatus status) {
        statuses.put(status.deviceId(), status);
    }

    public void update(DeviceStatus status) {
        statuses.put(status.deviceId(), status);
    }

    public void remove(String deviceId) {
        statuses.remove(deviceId);
        descriptors.remove(deviceId);
    }

    public List<DeviceStatus> all() {
        return statuses.values().stream().toList();
    }

    public List<DeviceStatus> byLocation(String location) {
        return statuses.values().stream()
            .filter(s -> location.equals(s.location()))
            .toList();
    }

    public DeviceStatus get(String deviceId) {
        return statuses.get(deviceId);
    }

    public Optional<DeviceDescriptor> descriptor(String deviceId) {
        return Optional.ofNullable(descriptors.get(deviceId));
    }

    public boolean sendCommand(String deviceId, String command, Object payload) {
        DeviceDescriptor desc = descriptors.get(deviceId);
        if (desc == null || !desc.enabled()) return false;
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return false;
        return adapter.sendCommand(desc, command, payload);
    }

    /**
     * Actively poll a device's adapter for its current state, bypassing the
     * passive last-seen cache. Empty means the device didn't answer (or has
     * no descriptor/adapter, or its adapter doesn't support polling — e.g.
     * MQTT devices are push-only and always return empty here).
     */
    public Optional<DeviceStatus> activeFetch(String deviceId) {
        DeviceDescriptor desc = descriptors.get(deviceId);
        if (desc == null) return Optional.empty();
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return Optional.empty();
        return adapter.fetchState(desc);
    }
}
