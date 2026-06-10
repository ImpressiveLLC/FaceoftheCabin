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
        // ── Cabin devices ────────────────────────────────────────────────────

        registerDescriptor(new DeviceDescriptor(
            "water-main-pressure", "Main Water Pressure", DeviceType.WATER_PRESSURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt",
            "cabin/device/water-main-pressure", true, "cabin"));

        registerDescriptor(new DeviceDescriptor(
            "cabin-thermostat-main", "Cabin Thermostat", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.cabin_thermostat", true, "cabin"));

        registerDescriptor(new DeviceDescriptor(
            "kidde-smoke-main", "Cabin Smoke Alarm", DeviceType.SMOKE_ALARM,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "ha_rest", "binary_sensor.kidde_smoke_alarm", true, "cabin"));

        registerDescriptor(new DeviceDescriptor(
            "front-door-lock", "Cabin Front Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.front_door", true, "cabin"));

        registerDescriptor(new DeviceDescriptor(
            "camera-front-door", "Cabin Front Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://192.168.1.101/stream1", false, "cabin"));

        registerDescriptor(new DeviceDescriptor(
            "bosch-dishwasher", "Cabin Bosch Dishwasher", DeviceType.DISHWASHER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.bosch_dishwasher_door", true, "cabin"));

        registerDescriptor(new DeviceDescriptor(
            "lg-washer", "Cabin LG Washing Machine", DeviceType.WASHING_MACHINE,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.lg_washer_state", true, "cabin"));

        // ── Home devices ─────────────────────────────────────────────────────

        // Reolink RLC-810A PoE cameras (RTSP → Frigate; IPs 192.168.1.20–24)
        registerDescriptor(new DeviceDescriptor(
            "home-cam-front", "Home Front Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.20:554/h264Preview_01_main",
            true, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-driveway", "Home Driveway Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.21:554/h264Preview_01_main",
            true, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-backyard", "Home Backyard Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.22:554/h264Preview_01_main",
            true, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-garage", "Home Garage Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.23:554/h264Preview_01_main",
            true, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-side", "Home Side Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.24:554/h264Preview_01_main",
            true, "home"));

        // Kwikset 916 Zigbee deadbolts (Aeotec Z-Stick 10 Pro → Zigbee2MQTT → HA)
        registerDescriptor(new DeviceDescriptor(
            "home-lock-front", "Home Front Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_front_door", true, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lock-back", "Home Back Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_back_door", true, "home"));

        // meross MTS300M Matter thermostat (HA Matter integration)
        registerDescriptor(new DeviceDescriptor(
            "home-thermostat-main", "Home Thermostat", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_thermostat", true, "home"));

        // Kidde P4010ACSCO-WF smoke/CO combo (HACS Kidde HomeSafe integration)
        registerDescriptor(new DeviceDescriptor(
            "home-smoke-co-main", "Home Smoke/CO Alarm", DeviceType.SMOKE_ALARM,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "ha_rest", "binary_sensor.home_kidde_smoke_co", true, "home"));

        // Emporia Vue Gen 3 energy monitor (HA Emporia Vue integration)
        registerDescriptor(new DeviceDescriptor(
            "home-energy-main", "Home Energy Monitor", DeviceType.POWER_METER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.POWER_MONITOR),
            "ha_rest", "sensor.home_emporia_total_power_w", true, "home"));

        // LG ThinQ washer + dryer (HA SmartThinQ integration — cloud poll, monitor only)
        registerDescriptor(new DeviceDescriptor(
            "home-lg-washer", "Home LG Washer", DeviceType.WASHING_MACHINE,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_washer_state", true, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lg-dryer", "Home LG Dryer", DeviceType.DRYER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_dryer_state", true, "home"));

        // Bosch 500 Series dishwasher (HA Home Connect integration — monitor + control)
        registerDescriptor(new DeviceDescriptor(
            "home-bosch-dishwasher", "Home Bosch Dishwasher", DeviceType.DISHWASHER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_bosch_dishwasher_door", true, "home"));

        // Daikin Aurora HVAC with BRP072A43 WiFi module (HA Daikin integration — local API)
        registerDescriptor(new DeviceDescriptor(
            "home-daikin-hvac", "Home Daikin Aurora HVAC", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_daikin_aurora", true, "home"));
    }

    public void registerDescriptor(DeviceDescriptor desc) {
        descriptors.put(desc.deviceId(), desc);
        if (!statuses.containsKey(desc.deviceId())) {
            statuses.put(desc.deviceId(), new DeviceStatus(
                desc.deviceId(), desc.type(), desc.name(), "UNKNOWN",
                Instant.now(), Map.of(), desc.location()));
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
        if (desc == null) return false;
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return false;
        return adapter.sendCommand(desc, command, payload);
    }
}
