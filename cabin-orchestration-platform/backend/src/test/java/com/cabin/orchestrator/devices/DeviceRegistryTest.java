package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.DeviceStatus;
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

class DeviceRegistryTest {

    private RecordingStore store;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        store = new RecordingStore();
        registry = new DeviceRegistry(List.of(), store);
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
        assertEquals("correct-model", status.attributes().get("model"));
        assertEquals(DeviceLifecycleState.CANDIDATE.name(), status.attributes().get("deviceLifecycle"));
        assertEquals(true, status.attributes().get("candidate"));
        assertTrue(store.records.isEmpty(), "passive discovery is not a person-authored persistence event");
    }

    @Test
    void savedCandidateRenameIsStickyButDoesNotAcceptCandidate() {
        registry.registerCandidate(descriptor(
            "candidate-2", "Discovered name", DeviceType.CONTACT_SENSOR,
            Set.of(DeviceCapability.ACCESS_CONTROL), "mqtt", "zigbee2mqtt/contact", false, "cabin"), Map.of());

        DeviceRegistry.ConfigurationSaveResult saved =
            registry.saveConfiguration("candidate-2", "Pantry door", false);
        registry.registerCandidate(descriptor(
            "candidate-2", "Raw source name again", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "new/topic", false, "home"), Map.of());

        var descriptor = registry.descriptor("candidate-2").orElseThrow();
        assertTrue(saved.changed());
        assertEquals(DeviceLifecycleState.CANDIDATE, saved.lifecycleState());
        assertEquals("Pantry door", descriptor.name());
        assertEquals(DeviceType.CONTACT_SENSOR, descriptor.type());
        assertEquals("cabin", descriptor.location());
        assertEquals("new/topic", descriptor.connectionString(), "source-owned connection still refreshes");
        assertEquals(DeviceLifecycleState.CANDIDATE, registry.lifecycleState("candidate-2"));
        assertEquals(true, registry.get("candidate-2").attributes().get("candidate"));
        assertTrue(store.records.get("candidate-2").configurationAsserted());
    }

    @Test
    void reviewingOrSavingNoEffectiveChangeLeavesCandidateAndWritesNothing() {
        registry.registerCandidate(descriptor(
            "candidate-3", "Entry motion", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/motion", false, "cabin"), Map.of());

        DeviceRegistry.ConfigurationSaveResult result =
            registry.saveConfiguration("candidate-3", "Entry motion", false);

        assertFalse(result.changed());
        assertEquals(DeviceLifecycleState.CANDIDATE, registry.lifecycleState("candidate-3"));
        assertTrue(store.records.isEmpty());
    }

    @Test
    void explicitAcceptanceMakesDeviceAvailableInScopeButUnassignedAndDisabled() {
        registry.registerCandidate(descriptor(
            "candidate-4", "Leak sensor", DeviceType.WATER_LEAK_SENSOR,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "mqtt", "zigbee2mqtt/leak", false, "cabin"), Map.of());

        DeviceRegistry.LifecycleChangeResult result =
            registry.applyLifecycleAction("candidate-4", DeviceLifecycleAction.ACCEPT);

        assertTrue(result.changed());
        assertEquals(DeviceLifecycleState.AVAILABLE, result.lifecycleState());
        assertFalse(registry.descriptor("candidate-4").orElseThrow().enabled());
        assertTrue(registry.inScope().stream().map(DeviceStatus::deviceId).toList().contains("candidate-4"));
        assertTrue(registry.candidates().isEmpty());
        assertEquals(DeviceLifecycleState.AVAILABLE, store.records.get("candidate-4").lifecycleState());
    }

    @Test
    void actualConfigurationChangeAssignsAnAvailableDevice() {
        registry.registerCandidate(descriptor(
            "candidate-5", "Door", DeviceType.CONTACT_SENSOR,
            Set.of(DeviceCapability.ACCESS_CONTROL), "mqtt", "zigbee2mqtt/door", false, "cabin"), Map.of());
        registry.applyLifecycleAction("candidate-5", DeviceLifecycleAction.ACCEPT);

        DeviceRegistry.ConfigurationSaveResult result =
            registry.saveConfiguration("candidate-5", "Front door", true);

        assertTrue(result.changed());
        assertEquals(DeviceLifecycleState.ASSIGNED, result.lifecycleState());
        assertTrue(result.descriptor().enabled());
        assertEquals(DeviceLifecycleState.ASSIGNED, store.records.get("candidate-5").lifecycleState());
    }

    @Test
    void candidateCannotBeEnabledWithoutExplicitAcceptance() {
        registry.registerCandidate(descriptor(
            "candidate-6", "Switch", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND), "ha_rest", "switch.test", false, "cabin"), Map.of());

        assertThrows(IllegalStateException.class,
            () -> registry.saveConfiguration("candidate-6", "Switch", true));
        assertEquals(DeviceLifecycleState.CANDIDATE, registry.lifecycleState("candidate-6"));
        assertTrue(store.records.isEmpty());
    }

    @Test
    void deferredAndIgnoredDevicesAreOnlyInPreviouslyExposedCacheAndRestoreAfterRestart() {
        registry.registerCandidate(descriptor(
            "later", "Later", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "later/topic", false, "cabin"), Map.of());
        registry.registerCandidate(descriptor(
            "ignored", "Ignored", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "ignored/topic", false, "cabin"), Map.of());
        registry.applyLifecycleAction("later", DeviceLifecycleAction.DEFER);
        registry.applyLifecycleAction("ignored", DeviceLifecycleAction.IGNORE);

        DeviceRegistry restarted = new DeviceRegistry(List.of(), store);

        assertFalse(restarted.inScope().stream().map(DeviceStatus::deviceId).toList().contains("later"));
        assertFalse(restarted.inScope().stream().map(DeviceStatus::deviceId).toList().contains("ignored"));
        assertTrue(restarted.candidates().isEmpty());
        assertEquals(Set.of("later", "ignored"), restarted.previouslyExposed().stream()
            .map(DeviceStatus::deviceId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(DeviceLifecycleState.DEFERRED, restarted.lifecycleState("later"));
        assertEquals(DeviceLifecycleState.IGNORED, restarted.lifecycleState("ignored"));
    }

    @Test
    void cachedAndAvailableDevicesCannotBeActivelyFetchedOrCommanded() {
        CountingAdapter adapter = new CountingAdapter();
        registry = new DeviceRegistry(List.of(adapter), store);
        registry.registerCandidate(descriptor(
            "secure-device", "Secure", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND), "test", "lock.secure", false, "cabin"), Map.of());
        registry.applyLifecycleAction("secure-device", DeviceLifecycleAction.ACCEPT);

        assertTrue(registry.activeFetch("secure-device").isEmpty());
        assertFalse(registry.sendCommand("secure-device", "lock.lock", null));
        registry.applyLifecycleAction("secure-device", DeviceLifecycleAction.IGNORE);
        assertTrue(registry.activeFetch("secure-device").isEmpty());
        assertFalse(registry.sendCommand("secure-device", "lock.lock", null));
        assertEquals(0, adapter.fetches);
        assertEquals(0, adapter.commands);
    }

    @Test
    void persistenceFailureLeavesCandidateStateUnchanged() {
        DeviceLifecycleStore failingStore = new DeviceLifecycleStore() {
            @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.of(); }
            @Override public void save(DeviceLifecycleRecord record) { throw new IllegalStateException("database unavailable"); }
            @Override public void delete(String deviceId) { throw new IllegalStateException("database unavailable"); }
        };
        registry = new DeviceRegistry(List.of(), failingStore);
        registry.registerCandidate(descriptor(
            "candidate-safe", "Safe", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "safe/topic", false, "cabin"), Map.of());

        assertThrows(IllegalStateException.class,
            () -> registry.applyLifecycleAction("candidate-safe", DeviceLifecycleAction.ACCEPT));
        assertEquals(DeviceLifecycleState.CANDIDATE, registry.lifecycleState("candidate-safe"));
        assertTrue(registry.candidates().stream().anyMatch(d -> d.deviceId().equals("candidate-safe")));
        assertTrue(registry.inScope().stream().noneMatch(d -> d.deviceId().equals("candidate-safe")));
    }

    @Test
    void assignedSafetyFieldsRemainStickyAcrossDegradedDiscovery() {
        registry.registerConfiguredDevice(descriptor(
            "configured", "Water alarm", DeviceType.WATER_LEAK_SENSOR,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "mqtt", "old/topic", true, "cabin"));

        registry.registerCandidate(descriptor(
            "configured", "Bad snapshot", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(), "mqtt", "new/topic", false, "home"), Map.of());

        DeviceDescriptor descriptor = registry.descriptor("configured").orElseThrow();
        assertEquals("Water alarm", descriptor.name());
        assertEquals(DeviceType.WATER_LEAK_SENSOR, descriptor.type());
        assertEquals(Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM), descriptor.capabilities());
        assertTrue(descriptor.enabled());
        assertEquals("cabin", descriptor.location());
        assertEquals("new/topic", descriptor.connectionString());
        assertEquals(DeviceLifecycleState.ASSIGNED, registry.lifecycleState("configured"));
    }

    private DeviceDescriptor descriptor(String id, String name, DeviceType type,
                                        Set<DeviceCapability> capabilities, String adapter,
                                        String connection, boolean enabled, String location) {
        return new DeviceDescriptor(id, name, type, capabilities, adapter, connection, enabled, location);
    }

    private static final class RecordingStore implements DeviceLifecycleStore {
        private final Map<String, DeviceLifecycleRecord> records = new LinkedHashMap<>();

        @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.copyOf(records); }
        @Override public void save(DeviceLifecycleRecord record) {
            records.put(record.descriptor().deviceId(), record);
        }
        @Override public void delete(String deviceId) { records.remove(deviceId); }
    }

    private static final class CountingAdapter implements ProtocolAdapter {
        private int fetches;
        private int commands;

        @Override public String adapterType() { return "test"; }
        @Override public Optional<DeviceStatus> fetchState(DeviceDescriptor descriptor) {
            fetches++;
            return Optional.of(new DeviceStatus(descriptor.deviceId(), descriptor.type(), descriptor.name(),
                "ONLINE", Instant.now(), Map.of(), descriptor.location()));
        }
        @Override public boolean sendCommand(DeviceDescriptor descriptor, String command, Object payload) {
            commands++;
            return true;
        }
    }
}
