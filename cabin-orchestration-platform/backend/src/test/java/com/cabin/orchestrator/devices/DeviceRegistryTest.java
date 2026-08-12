package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DeviceRegistryTest {

    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
    }

    @Test
    void repeatedCandidateDiscoveryRefreshesSourceOwnedFields() {
        registry.registerCandidate(descriptor(
            "candidate-1", "Wrong valve name", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "old/topic", false, "wrong-location"),
            Map.of("model", "old-model"));

        registry.registerCandidate(descriptor(
            "candidate-1", "Kitchen temperature", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.CLIMATE),
            "ha_rest", "sensor.kitchen_temperature", false, "cabin"),
            Map.of("model", "correct-model"));

        var descriptor = registry.descriptor("candidate-1").orElseThrow();
        var status = registry.get("candidate-1");
        assertEquals("Kitchen temperature", descriptor.name());
        assertEquals(DeviceType.TEMPERATURE_SENSOR, descriptor.type());
        assertEquals(Set.of(DeviceCapability.TELEMETRY, DeviceCapability.CLIMATE), descriptor.capabilities());
        assertEquals("ha_rest", descriptor.protocolAdapter());
        assertEquals("sensor.kitchen_temperature", descriptor.connectionString());
        assertEquals("cabin", descriptor.location());
        assertEquals("Kitchen temperature", status.name());
        assertEquals(DeviceType.TEMPERATURE_SENSOR, status.type());
        assertEquals("correct-model", status.attributes().get("model"));
        assertEquals(true, status.attributes().get("candidate"));
        assertEquals(false, status.attributes().get("enabled"));
    }

    @Test
    void configuredDeviceKeepsHumanFieldsAndNeverBecomesCandidateAgain() {
        registry.registerCandidate(descriptor(
            "candidate-2", "Discovered name", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "old/topic", false, "cabin"), Map.of());

        registry.registerDescriptor(descriptor(
            "candidate-2", "My chosen name", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "old/topic", true, "home"));
        registry.registerDescriptor(descriptor(
            "candidate-2", "My chosen name", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "old/topic", false, "home"));

        registry.registerCandidate(descriptor(
            "candidate-2", "New source name", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.CLIMATE),
            "ha_rest", "sensor.new_source", false, "cabin"), Map.of("model", "new-model"));

        var descriptor = registry.descriptor("candidate-2").orElseThrow();
        var status = registry.get("candidate-2");
        assertEquals("My chosen name", descriptor.name(), "a configured display name is person-owned");
        assertEquals("home", descriptor.location(), "a configured location is person-owned");
        assertFalse(descriptor.enabled(), "discovery must not re-enable a device a person disabled");
        assertEquals(DeviceType.TEMPERATURE_SENSOR, descriptor.type(), "source type can be corrected");
        assertEquals(Set.of(DeviceCapability.TELEMETRY, DeviceCapability.CLIMATE), descriptor.capabilities());
        assertEquals("ha_rest", descriptor.protocolAdapter());
        assertEquals("sensor.new_source", descriptor.connectionString());
        assertEquals("My chosen name", status.name());
        assertEquals(false, status.attributes().get("candidate"));
        assertEquals(false, status.attributes().get("enabled"));
    }

    @Test
    void enablingCandidateSynchronizesStatusAndClearsCandidateMarker() {
        registry.registerCandidate(descriptor(
            "candidate-3", "Discovered", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/motion", false, "cabin"), Map.of());

        registry.registerDescriptor(descriptor(
            "candidate-3", "Entry motion", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/motion", true, "cabin"));

        var status = registry.get("candidate-3");
        assertEquals("Entry motion", status.name());
        assertEquals(false, status.attributes().get("candidate"));
        assertEquals(true, status.attributes().get("enabled"));
    }

    private DeviceDescriptor descriptor(String id, String name, DeviceType type,
                                        Set<DeviceCapability> capabilities, String adapter,
                                        String connection, boolean enabled, String location) {
        return new DeviceDescriptor(id, name, type, capabilities, adapter, connection, enabled, location);
    }
}
