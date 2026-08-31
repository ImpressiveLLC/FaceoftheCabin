package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JsonLdServiceTest {

    private DeviceRegistry registry;
    private FakeDeviceRepository deviceRepository;
    private FakeReportingRelationshipRepository reportingRepository;
    private JsonLdService service;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        deviceRepository = new FakeDeviceRepository();
        reportingRepository = new FakeReportingRelationshipRepository();
        service = new JsonLdService(registry, deviceRepository, reportingRepository);
    }

    private void confirm(String deviceId, String name, Set<DeviceCapability> caps) {
        registry.registerCandidate(new DeviceDescriptor(
            deviceId, name, DeviceType.TEMPERATURE_SENSOR, caps, "mqtt", "topic/" + deviceId, true, "cabin"), Map.of());
        registry.applyLifecycleAction(deviceId, DeviceLifecycleAction.ACCEPT);
    }

    @Test
    void unknownDeviceIsAbsent() {
        assertTrue(service.deviceAsJsonLd("z2m-never_seen").isEmpty());
    }

    @Test
    void knownDeviceWithFullMetadataIncludesEveryField() {
        confirm("z2m-temp_kitchen", "Kitchen temp", Set.of(DeviceCapability.TELEMETRY));
        deviceRepository.put("z2m-temp_kitchen", new DeviceMetadata(
            "SONOFF", "SNZB-02WD", "mech_room", Instant.now(), "system", Instant.now(), "system", Instant.now(), 1));
        reportingRepository.add("z2m-temp_kitchen", "temperature");
        reportingRepository.add("z2m-temp_kitchen", "humidity");

        Map<String, Object> node = service.deviceAsJsonLd("z2m-temp_kitchen").orElseThrow();

        assertEquals("cabin:z2m-temp_kitchen", node.get("id"));
        assertEquals("Device", node.get("type"), "TELEMETRY-only capability must not be classified as an Actuator");
        assertEquals("Kitchen temp", node.get("name"));
        assertEquals("SONOFF", node.get("manufacturer"));
        assertEquals("SNZB-02WD", node.get("model"));
        assertEquals("mech_room", node.get("area"));
        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) node.get("reportsField");
        assertEquals(List.of("humidity", "temperature"), fields);
    }

    @Test
    void aCommandCapableDeviceIsTypedAsActuator() {
        confirm("z2m-main_water_valve", "Main water valve", Set.of(DeviceCapability.COMMAND));

        Map<String, Object> node = service.deviceAsJsonLd("z2m-main_water_valve").orElseThrow();

        assertEquals("Actuator", node.get("type"));
    }

    @Test
    void aDeviceWithNoMetadataOrReportingDataOmitsThoseKeysEntirely() {
        confirm("z2m-bare_device", "Bare device", Set.of(DeviceCapability.TELEMETRY));

        Map<String, Object> node = service.deviceAsJsonLd("z2m-bare_device").orElseThrow();

        assertFalse(node.containsKey("manufacturer"));
        assertFalse(node.containsKey("model"));
        assertFalse(node.containsKey("area"));
        assertFalse(node.containsKey("reportsField"), "an empty list would be noise, not a fact -- same discipline as KbGeneratorService");
    }

    private static final class FakeDeviceRepository implements DeviceRepository {
        private final Map<String, DeviceMetadata> data = new LinkedHashMap<>();
        void put(String deviceId, DeviceMetadata metadata) { data.put(deviceId, metadata); }
        @Override public void upsert(String deviceId, DeviceMetadata metadata) { data.put(deviceId, metadata); }
        @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.ofNullable(data.get(deviceId)); }
        @Override public Map<String, DeviceMetadata> loadAll() { return Map.copyOf(data); }
    }

    private static final class FakeReportingRelationshipRepository implements DeviceReportingRelationshipRepository {
        private final Map<String, List<DeviceReportingRelationship>> data = new LinkedHashMap<>();
        void add(String deviceId, String field) {
            data.computeIfAbsent(deviceId, k -> new java.util.ArrayList<>()).add(new DeviceReportingRelationship(
                deviceId, field, field, ConfirmationSource.VENDOR_SPEC, Instant.now()));
        }
        @Override public void upsert(DeviceReportingRelationship relationship) { }
        @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) {
            return data.getOrDefault(deviceId, List.of());
        }
        @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.copyOf(data); }
    }
}
