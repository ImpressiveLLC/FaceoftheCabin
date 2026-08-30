package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
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
    void registerCandidateUpsertsManufacturerAndModelWhenDiscoveryProvidesThem() {
        RecordingDeviceRepository deviceRepository = new RecordingDeviceRepository();
        DeviceRegistry withMetadata = new DeviceRegistry(List.of(), store, deviceRepository);

        withMetadata.registerCandidate(descriptor(
            "z2m-temp_kitchen", "Kitchen temp", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/temp_kitchen", false, "cabin"),
            Map.of("vendor", "SONOFF", "model", "SNZB-02WD"));

        var metadata = deviceRepository.find("z2m-temp_kitchen").orElseThrow();
        assertEquals("SONOFF", metadata.manufacturer());
        assertEquals("SNZB-02WD", metadata.model());
    }

    @Test
    void registerCandidateDoesNotUpsertWhenDiscoveryHasNeitherVendorNorModel() {
        RecordingDeviceRepository deviceRepository = new RecordingDeviceRepository();
        DeviceRegistry withMetadata = new DeviceRegistry(List.of(), store, deviceRepository);

        withMetadata.registerCandidate(descriptor(
            "ha-generic", "Generic entity", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "ha_rest", "sensor.generic", false, "cabin"),
            Map.of("discoveredFrom", "Home Assistant"));

        assertTrue(deviceRepository.find("ha-generic").isEmpty(),
            "an adapter that never populates vendor/model must not overwrite metadata with nulls");
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

    /**
     * 2026-08-18: room (UX rework's wired-up grouping dimension) rides
     * saveConfiguration's new extraAttributes parameter -- see
     * DeviceLifecycleRecord's own comment for why it's not a real
     * DeviceDescriptor field. Must be visible on the live status
     * immediately (not just after a restart -- that round-trip is
     * JdbcDeviceLifecycleStoreIntegrationTest's job).
     */
    @Test
    void savingRoomAsExtraAttributesIsVisibleOnTheLiveStatusImmediately() {
        registry.registerCandidate(descriptor(
            "candidate-room", "Kitchen temp", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/temp", false, "cabin"), Map.of());

        DeviceRegistry.ConfigurationSaveResult saved = registry.saveConfiguration(
            "candidate-room", "Kitchen temp", false, Map.of("room", "Kitchen"));

        assertTrue(saved.changed());
        assertEquals("Kitchen", registry.get("candidate-room").attributes().get("room"));
        assertEquals("Kitchen", store.records.get("candidate-room").extraAttributes().get("room"));
    }

    /**
     * The changed() short-circuit above name/enabled must not swallow a
     * room-only edit -- name and enabled are unchanged here, only room is.
     */
    @Test
    void roomOnlyChangeIsStillTreatedAsAChangeEvenWhenNameAndEnabledMatch() {
        registry.registerCandidate(descriptor(
            "candidate-room-2", "Front door lock", DeviceType.LOCK,
            Set.of(DeviceCapability.ACCESS_CONTROL), "ha_rest", "lock.front_door", false, "cabin"), Map.of());
        registry.saveConfiguration("candidate-room-2", "Front door lock", false);

        DeviceRegistry.ConfigurationSaveResult saved = registry.saveConfiguration(
            "candidate-room-2", "Front door lock", false, Map.of("room", "Entry"));

        assertTrue(saved.changed(), "a room-only edit must still count as a real, persisted change");
        assertEquals("Entry", registry.get("candidate-room-2").attributes().get("room"));
    }

    /**
     * 2026-08-25: parentDeviceId (device→services hierarchy's MVP link,
     * e.g. a Kidde unit's several HA entities pointing back at one
     * physical device) rides the same extraAttributes slot as room, but
     * unlike room it's a real referential invariant, not free text --
     * see validateParentDeviceId's own comment for why the validation
     * lives in DeviceRegistry rather than the frontend/controller.
     */
    @Test
    void savingAValidParentDeviceIdIsVisibleOnTheLiveStatusImmediately() {
        registry.registerConfiguredDevice(descriptor(
            "kidde-unit", "Kidde CO/Air Quality Unit", DeviceType.CO_ALARM,
            Set.of(DeviceCapability.TELEMETRY), "ha_rest", "device.kidde", true, "cabin"));
        registry.registerConfiguredDevice(descriptor(
            "kidde-co-alarm", "CO Alarm", DeviceType.CO_ALARM,
            Set.of(DeviceCapability.ALARM), "ha_rest", "binary_sensor.kidde_co", true, "cabin"));

        DeviceRegistry.ConfigurationSaveResult saved = registry.saveConfiguration(
            "kidde-co-alarm", "CO Alarm", true, Map.of("parentDeviceId", "kidde-unit"));

        assertTrue(saved.changed());
        assertEquals("kidde-unit", registry.get("kidde-co-alarm").attributes().get("parentDeviceId"));
    }

    @Test
    void clearingAParentDeviceIdWithABlankValueIsAlwaysValid() {
        registry.registerConfiguredDevice(descriptor(
            "parent-a", "Parent", DeviceType.CO_ALARM, Set.of(), "ha_rest", "x", true, "cabin"));
        registry.registerConfiguredDevice(descriptor(
            "child-a", "Child", DeviceType.CO_ALARM, Set.of(), "ha_rest", "y", true, "cabin"));
        registry.saveConfiguration("child-a", "Child", true, Map.of("parentDeviceId", "parent-a"));

        DeviceRegistry.ConfigurationSaveResult saved = registry.saveConfiguration(
            "child-a", "Child", true, Map.of("parentDeviceId", ""));

        assertTrue(saved.changed());
        assertEquals("", registry.get("child-a").attributes().get("parentDeviceId"));
    }

    @Test
    void aDeviceCannotBeSetAsItsOwnParent() {
        registry.registerConfiguredDevice(descriptor(
            "self-parent", "Self", DeviceType.CO_ALARM, Set.of(), "ha_rest", "x", true, "cabin"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            registry.saveConfiguration("self-parent", "Self", true, Map.of("parentDeviceId", "self-parent")));
        assertTrue(error.getMessage().contains("own parent"));
    }

    @Test
    void aNonexistentParentDeviceIsRejected() {
        registry.registerConfiguredDevice(descriptor(
            "orphan", "Orphan", DeviceType.CO_ALARM, Set.of(), "ha_rest", "x", true, "cabin"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            registry.saveConfiguration("orphan", "Orphan", true, Map.of("parentDeviceId", "does-not-exist")));
        assertTrue(error.getMessage().contains("not found"));
    }

    @Test
    void aParentAtADifferentLocationIsRejected() {
        registry.registerConfiguredDevice(descriptor(
            "home-parent", "Home Parent", DeviceType.CO_ALARM, Set.of(), "ha_rest", "x", true, "home"));
        registry.registerConfiguredDevice(descriptor(
            "cabin-child", "Cabin Child", DeviceType.CO_ALARM, Set.of(), "ha_rest", "y", true, "cabin"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            registry.saveConfiguration("cabin-child", "Cabin Child", true, Map.of("parentDeviceId", "home-parent")));
        assertTrue(error.getMessage().contains("same location"));
    }

    @Test
    void settingAParentThatWouldCreateACycleIsRejected() {
        registry.registerConfiguredDevice(descriptor(
            "device-a", "A", DeviceType.CO_ALARM, Set.of(), "ha_rest", "a", true, "cabin"));
        registry.registerConfiguredDevice(descriptor(
            "device-b", "B", DeviceType.CO_ALARM, Set.of(), "ha_rest", "b", true, "cabin"));
        // B's parent is A.
        registry.saveConfiguration("device-b", "B", true, Map.of("parentDeviceId", "device-a"));

        // Now trying to set A's parent to B would form a 2-node cycle (A -> B -> A).
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
            registry.saveConfiguration("device-a", "A", true, Map.of("parentDeviceId", "device-b")));
        assertTrue(error.getMessage().contains("cycle"));
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
    void savingCandidateEnabledAtomicallyAcceptsAndAssignsIt() {
        registry.registerCandidate(descriptor(
            "candidate-6", "Switch", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND), "ha_rest", "switch.test", false, "cabin"), Map.of());

        DeviceRegistry.ConfigurationSaveResult result =
            registry.saveConfiguration("candidate-6", "Switch", true);

        assertTrue(result.changed());
        assertEquals(DeviceLifecycleState.ASSIGNED, result.lifecycleState());
        assertTrue(result.descriptor().enabled());
        assertEquals(DeviceLifecycleState.ASSIGNED, registry.lifecycleState("candidate-6"));
        assertEquals(DeviceLifecycleState.ASSIGNED, store.records.get("candidate-6").lifecycleState());
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

    @Test
    void replaceConfigurationOnlyTouchesSelectedFieldsAndNeverEnabled() {
        registry.registerConfiguredDevice(descriptor(
            "resync-target", "Old name", DeviceType.CONTACT_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/x", true, "cabin"));

        DeviceDescriptor proposed = new DeviceDescriptor(
            "resync-target", "New name from discovery", DeviceType.CONTACT_SENSOR,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ACCESS_CONTROL),
            "mqtt", "zigbee2mqtt/x", false /* must be ignored */, "home" /* not selected, must be ignored */);

        DeviceRegistry.ConfigurationSaveResult result =
            registry.replaceConfiguration("resync-target", proposed, Set.of("name", "capabilities"));

        assertTrue(result.changed());
        assertEquals("New name from discovery", result.descriptor().name());
        assertEquals(Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ACCESS_CONTROL), result.descriptor().capabilities());
        assertTrue(result.descriptor().enabled(), "enabled must never change via replaceConfiguration");
        assertEquals("cabin", result.descriptor().location(), "unselected fields must not change");
        assertEquals(DeviceLifecycleState.ASSIGNED, registry.lifecycleState("resync-target"));
    }

    @Test
    void replaceConfigurationRefusesOnAnUnreviewedCandidate() {
        registry.registerCandidate(descriptor(
            "resync-candidate", "Discovered", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/y", false, "cabin"), Map.of());

        DeviceDescriptor proposed = new DeviceDescriptor(
            "resync-candidate", "Renamed", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/y", false, "cabin");

        assertThrows(IllegalStateException.class,
            () -> registry.replaceConfiguration("resync-candidate", proposed, Set.of("name")));
    }

    @Test
    void replaceConfigurationRefusesOnAPreviouslyExposedDevice() {
        registry.registerCandidate(descriptor(
            "resync-deferred", "Discovered", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/z", false, "cabin"), Map.of());
        registry.applyLifecycleAction("resync-deferred", DeviceLifecycleAction.DEFER);

        DeviceDescriptor proposed = new DeviceDescriptor(
            "resync-deferred", "Renamed", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/z", false, "cabin");

        assertThrows(IllegalStateException.class,
            () -> registry.replaceConfiguration("resync-deferred", proposed, Set.of("name")));
    }

    @Test
    void replaceConfigurationIsANoOpWhenNothingSelectedChanges() {
        registry.registerConfiguredDevice(descriptor(
            "resync-noop", "Same name", DeviceType.CONTACT_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/w", true, "cabin"));

        DeviceDescriptor proposed = new DeviceDescriptor(
            "resync-noop", "Same name", DeviceType.CONTACT_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/w", true, "cabin");

        DeviceRegistry.ConfigurationSaveResult result =
            registry.replaceConfiguration("resync-noop", proposed, Set.of("name"));

        assertFalse(result.changed());
    }

    @Test
    void firstSeenCandidateGetsDiscoverySuggestedButRepeatedDiscoveryDoesNot() {
        registry.registerCandidate(descriptor(
            "new-1", "Mystery sensor", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/mystery", false, "cabin"), Map.of());

        assertEquals(true, registry.get("new-1").attributes().get("discoverySuggested"));

        registry.registerCandidate(descriptor(
            "new-1", "Mystery sensor", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/mystery", false, "cabin"), Map.of());

        assertEquals(true, registry.get("new-1").attributes().get("discoverySuggested"),
            "still true -- only an explicit dismiss or lifecycle decision clears it, not rediscovery");
    }

    @Test
    void discoverySuggestedReappearsAfterARestartBecausePassiveDiscoveryIsNeverPersisted() {
        // registerCandidate() intentionally never calls lifecycleStore.save()
        // (see repeatedCandidateDiscoveryRefreshesSourceOwnedFields) --
        // passive discovery of an undecided device is not a person-authored
        // persistence event. That means a still-undecided candidate does
        // not survive a process restart, so a fresh registry sees it as
        // firstSeen again on the next republish -- discoverySuggested
        // legitimately re-fires rather than being lost for good.
        registry.registerCandidate(descriptor(
            "new-1", "Mystery sensor", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/mystery", false, "cabin"), Map.of());

        DeviceRegistry restarted = new DeviceRegistry(List.of(), store);
        restarted.registerCandidate(descriptor(
            "new-1", "Mystery sensor", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/mystery", false, "cabin"), Map.of());

        assertEquals(true, restarted.get("new-1").attributes().get("discoverySuggested"));
    }

    @Test
    void discoverySuggestedIsClearedWhenCandidateLeavesReview() {
        registry.registerCandidate(descriptor(
            "new-2", "Mystery switch", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND), "mqtt", "zigbee2mqtt/mystery2", false, "cabin"), Map.of());
        assertEquals(true, registry.get("new-2").attributes().get("discoverySuggested"));

        registry.applyLifecycleAction("new-2", DeviceLifecycleAction.ACCEPT);

        assertNull(registry.get("new-2").attributes().get("discoverySuggested"));
    }

    @Test
    void dismissDiscoverySuggestionClearsFlagWithoutTouchingLifecycleOrOtherAttributes() {
        registry.registerCandidate(descriptor(
            "new-3", "Mystery leak sensor", DeviceType.WATER_LEAK_SENSOR,
            Set.of(DeviceCapability.ALARM), "mqtt", "zigbee2mqtt/mystery3", false, "cabin"),
            Map.of("vendor", "SONOFF"));

        registry.dismissDiscoverySuggestion("new-3");

        var status = registry.get("new-3");
        assertNull(status.attributes().get("discoverySuggested"));
        assertEquals("SONOFF", status.attributes().get("vendor"));
        assertEquals(DeviceLifecycleState.CANDIDATE, registry.lifecycleState("new-3"));
    }

    // ── Ontology metadata on read (added 2026-08-19) ──

    @Test
    void getAttachesCategoryAndCapabilitiesFromTheRealDescriptor() {
        registry.registerCandidate(descriptor(
            "cam-1", "Driveway", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.TELEMETRY), "mqtt", "cabin/camera/driveway", false, "cabin"),
            Map.of());

        var status = registry.get("cam-1");

        assertEquals("SECURITY", status.attributes().get("category"));
        assertEquals(List.of("STREAM", "TELEMETRY"), status.attributes().get("capabilities"));
    }

    @Test
    void getAttachesReportsFieldsDerivedFromDeviceTypeNotJustCategoryAndCapabilities() {
        // A Zigbee combo sensor is ONE entity typed TEMPERATURE_SENSOR that
        // also reports humidity -- reportsFields (added 2026-08-27) is what
        // lets Device Manager's device detail and a workflow's device-scoping
        // picker both see that, instead of only ever knowing about
        // "temperature" from the type name alone.
        registry.registerCandidate(descriptor(
            "temp-1", "Kitchen", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/temp_kitchen", false, "cabin"),
            Map.of());

        assertEquals(List.of("humidity", "temperature"), registry.get("temp-1").attributes().get("reportsFields"));
    }

    @Test
    void getAttachesAnEmptyReportsFieldsListForATypeThatReportsNoChartableField() {
        registry.registerCandidate(descriptor(
            "lock-1", "Front Door", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND), "zwave", "lock/front_door", false, "cabin"),
            Map.of());

        assertEquals(List.of(), registry.get("lock-1").attributes().get("reportsFields"));
    }

    @Test
    void categoryReflectsTheDeviceTypeEvenWithNoDescriptorLookupNeeded() {
        registry.registerCandidate(descriptor(
            "thermo-1", "Living Room", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.CLIMATE, DeviceCapability.COMMAND), "ha_rest", "climate.living_room", false, "cabin"),
            Map.of());

        assertEquals("CLIMATE", registry.get("thermo-1").attributes().get("category"));
    }

    @Test
    void ontologyMetadataIsAttachedAcrossEveryReadPathNotJustGet() {
        registry.registerCandidate(descriptor(
            "leak-1", "Mech Room", DeviceType.WATER_LEAK_SENSOR,
            Set.of(DeviceCapability.ALARM, DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/leak", false, "cabin"),
            Map.of());

        assertEquals("SAFETY", registry.visible().stream()
            .filter(d -> d.deviceId().equals("leak-1")).findFirst().orElseThrow().attributes().get("category"));
        assertEquals("SAFETY", registry.candidates().stream()
            .filter(d -> d.deviceId().equals("leak-1")).findFirst().orElseThrow().attributes().get("category"));
        assertEquals("SAFETY", registry.byLocation("cabin").stream()
            .filter(d -> d.deviceId().equals("leak-1")).findFirst().orElseThrow().attributes().get("category"));
    }

    @Test
    void ontologyMetadataDoesNotOverwriteUnrelatedExistingAttributes() {
        registry.registerCandidate(descriptor(
            "leak-2", "Bathroom", DeviceType.WATER_LEAK_SENSOR,
            Set.of(DeviceCapability.ALARM), "mqtt", "zigbee2mqtt/leak2", false, "cabin"),
            Map.of("vendor", "Third Reality", "battery", 100));

        var status = registry.get("leak-2");

        assertEquals("Third Reality", status.attributes().get("vendor"));
        assertEquals(100, status.attributes().get("battery"));
        assertEquals("SAFETY", status.attributes().get("category"));
    }

    private DeviceDescriptor descriptor(String id, String name, DeviceType type,
                                        Set<DeviceCapability> capabilities, String adapter,
                                        String connection, boolean enabled, String location) {
        return new DeviceDescriptor(id, name, type, capabilities, adapter, connection, enabled, location);
    }

    private static final class RecordingDeviceRepository implements DeviceRepository {
        private final Map<String, DeviceMetadata> saved = new LinkedHashMap<>();

        @Override public void upsert(String deviceId, DeviceMetadata metadata) {
            saved.put(deviceId, metadata);
        }
        @Override public Optional<DeviceMetadata> find(String deviceId) {
            return Optional.ofNullable(saved.get(deviceId));
        }
        @Override public Map<String, DeviceMetadata> loadAll() { return Map.copyOf(saved); }
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
