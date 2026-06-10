package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.*;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all devices.
 * - DeviceDescriptor: static config (capabilities, adapter, connection)
 * - DeviceStatus: runtime state (updated by MQTT bridge and HA polling)
 * Dispatches commands to the correct ProtocolAdapter.
 */
@Component
public class DeviceRegistry {

    private final Map<String, DeviceStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, DeviceDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, ProtocolAdapter> adapters = new ConcurrentHashMap<>();

    /** Called by Spring to inject all ProtocolAdapter beans */
    public DeviceRegistry(List<ProtocolAdapter> adapterList) {
        adapterList.forEach(a -> adapters.put(a.adapterType(), a));
        seedDefaults();
    }

    private void seedDefaults() {
        // Seeds known devices; override with DB-backed persistence later
        registerDescriptor(new DeviceDescriptor(
            "water-main-pressure", "Main Water Pressure", DeviceType.WATER_PRESSURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt",
            "cabin/device/water-main-pressure", true));

        registerDescriptor(new DeviceDescriptor(
            "cabin-thermostat-main", "Main Thermostat", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.cabin_thermostat", true));

        registerDescriptor(new DeviceDescriptor(
            "kidde-smoke-main", "Main Smoke Alarm", DeviceType.SMOKE_ALARM,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "ha_rest", "binary_sensor.kidde_smoke_alarm", true));

        registerDescriptor(new DeviceDescriptor(
            "front-door-lock", "Front Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.front_door", true));

        registerDescriptor(new DeviceDescriptor(
            "camera-front-door", "Front Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://192.168.1.101/stream1", false));  // disabled until camera installed

        registerDescriptor(new DeviceDescriptor(
            "bosch-dishwasher", "Bosch Dishwasher", DeviceType.DISHWASHER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.bosch_dishwasher_door", true));

        registerDescriptor(new DeviceDescriptor(
            "lg-washer", "LG Washing Machine", DeviceType.WASHING_MACHINE,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.lg_washer_state", true));
    }

    public void registerDescriptor(DeviceDescriptor desc) {
        descriptors.put(desc.deviceId(), desc);
        if (!statuses.containsKey(desc.deviceId())) {
            statuses.put(desc.deviceId(), new DeviceStatus(
                desc.deviceId(), desc.type(), desc.name(), "UNKNOWN", Instant.now(), Map.of()));
        }
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

    public DeviceStatus get(String deviceId) {
        return statuses.get(deviceId);
    }

    public Optional<DeviceDescriptor> descriptor(String deviceId) {
        return Optional.ofNullable(descriptors.get(deviceId));
    }

    public boolean sendCommand(String deviceId, String command, Object payload) {
        DeviceDescriptor desc = descriptors.get(deviceId);
        if (desc == null) return false;
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return false;
        return adapter.sendCommand(desc, command, payload);
    }
}
