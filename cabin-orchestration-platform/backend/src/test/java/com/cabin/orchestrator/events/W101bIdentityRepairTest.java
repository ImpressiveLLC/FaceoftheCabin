package com.cabin.orchestrator.events;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * W-101b: the states the identity and alias repair plan (docs/governance/evidence/w101b-dryrun-2026-10-27)
 * would produce, checked against the registry rules and the history fold. Tests only; nothing here applies a
 * repair to a live system. T1 to T6 follow the plan's test column, T7 is the former-id history alias (D3).
 */
class W101bIdentityRepairTest {

    private static final String MECH_Z2M = "z2m-temp_mech_room";
    private static final String MECH_ROOT = "ha-cabin-sensor-mech-room-temp-and-humidity-temperature";
    private static final String MECH_HUMIDITY = "ha-cabin-sensor-mech-room-temp-and-humidity-humidity";
    private static final String MECH_BATTERY = "ha-cabin-sensor-mech-room-temp-and-humidity-battery";
    private static final String MECH_CALIBRATION = "ha-cabin-number-mech-room-temp-and-humidity-temperature-calibration";

    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
    }

    private void zigbee(String id, String friendly, DeviceType type) {
        registry.registerDescriptor(new DeviceDescriptor(id, friendly, type,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/" + friendly, true, "cabin"));
    }

    private void entity(String id, String entityId, DeviceType type, String parentId) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("entityId", entityId);
        if (parentId != null) attrs.put("parentDeviceId", parentId);
        registry.registerCandidate(new DeviceDescriptor(id, entityId, type,
            Set.of(DeviceCapability.TELEMETRY), "ha_rest", entityId, false, "cabin"), attrs);
    }

    private String parentOf(String id) {
        Object p = registry.get(id).attributes().get("parentDeviceId");
        return p == null ? "" : String.valueOf(p);
    }

    private void mechRoomBefore() {
        zigbee(MECH_Z2M, "temp_mech_room", DeviceType.TEMPERATURE_SENSOR);
        entity(MECH_ROOT, "sensor.mech_room_temp_and_humidity_temperature", DeviceType.TEMPERATURE_SENSOR, null);
        entity(MECH_HUMIDITY, "sensor.mech_room_temp_and_humidity_humidity", DeviceType.HUMIDITY_SENSOR, MECH_ROOT);
        entity(MECH_BATTERY, "sensor.mech_room_temp_and_humidity_battery", DeviceType.HOME_ASSISTANT_ENTITY, MECH_ROOT);
        entity(MECH_CALIBRATION, "number.mech_room_temperature_calibration", DeviceType.HOME_ASSISTANT_ENTITY, MECH_ROOT);
    }

    private void mechRoomAfter() {
        for (String id : List.of(MECH_ROOT, MECH_HUMIDITY, MECH_BATTERY, MECH_CALIBRATION)) {
            assertTrue(registry.setDiscoveredParent(id, MECH_Z2M), id);
        }
    }

    @Test
    void t1_theFlattenedParentsAreAcceptedWithNoCycleAndOneParentPerRow() {
        mechRoomBefore();
        mechRoomAfter();
        for (String id : List.of(MECH_ROOT, MECH_HUMIDITY, MECH_BATTERY, MECH_CALIBRATION)) {
            assertEquals(MECH_Z2M, parentOf(id), id);
        }
        assertEquals("", parentOf(MECH_Z2M));
        // A cycle back onto a child is still refused after the repair.
        assertThrows(IllegalArgumentException.class, () -> registry.setDiscoveredParent(MECH_Z2M, MECH_HUMIDITY));
        // A parent with no row is refused: why rollback of a dangling value is a host-run restore, not a PATCH.
        assertThrows(IllegalArgumentException.class, () -> registry.setDiscoveredParent(MECH_BATTERY, "ha-cabin-no-such-parent"));
    }

    @Test
    void t2_afterTheRepairTheMechRoomHasOneRootAndNoEntityIsAParentOfAnother() {
        mechRoomBefore();
        mechRoomAfter();
        List<String> roots = registry.all().stream()
            .filter(s -> s.deviceId().equals(MECH_Z2M) || s.deviceId().startsWith("ha-cabin-") && s.deviceId().contains("mech-room"))
            .filter(s -> parentOf(s.deviceId()).isEmpty()).map(DeviceStatus::deviceId).toList();
        assertEquals(List.of(MECH_Z2M), roots);
        assertTrue(registry.all().stream().noneMatch(s -> parentOf(s.deviceId()).equals(MECH_ROOT)));
    }

    @Test
    void t3_theMechRoomChartAndPickerReadOneSeriesWithNoReadingDropped() {
        mechRoomBefore();
        mechRoomAfter();
        // The temperature, humidity and battery entities are sensor.* children; the calibration number is not folded.
        List<String> ids = ServiceHistory.idsFor(MECH_Z2M, registry);
        assertEquals(MECH_Z2M, ids.get(0));
        assertEquals(Set.of(MECH_Z2M, MECH_ROOT, MECH_HUMIDITY, MECH_BATTERY), Set.copyOf(ids));
        assertEquals(4, ids.size());

        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put(MECH_Z2M, List.of("temperature"));
        raw.put(MECH_ROOT, List.of("temperature"));
        raw.put(MECH_HUMIDITY, List.of("humidity"));
        Map<String, List<String>> merged = ServiceHistory.mergeReportedFields(raw, registry);
        assertEquals(List.of(MECH_Z2M), List.copyOf(merged.keySet()));
        assertEquals(List.of("temperature", "humidity"), merged.get(MECH_Z2M));
    }

    @Test
    void t4_theBathroomLeakServiceRowsAttachToTheZigbeeParent() {
        zigbee("z2m-leak_alarm_bathroom", "leak_alarm_bathroom", DeviceType.WATER_LEAK_SENSOR);
        String dangling = "ha-cabin-switch-bathroom-leak-detect-alarm";
        String leak = "ha-cabin-binary-sensor-bathroom-leak-detect-alarm-water-leak";
        String battery = "ha-cabin-sensor-bathroom-leak-detect-alarm-battery";
        String siren = "ha-cabin-switch-bathroom-leak-detect-alarm-water-leak-buzzer";
        entity(leak, "binary_sensor.bathroom_leak_detect_alarm_water_leak", DeviceType.WATER_LEAK_SENSOR, dangling);
        entity(battery, "sensor.bathroom_leak_detect_alarm_battery", DeviceType.HOME_ASSISTANT_ENTITY, dangling);
        entity(siren, "switch.bathroom_leak_detect_alarm_water_leak_buzzer", DeviceType.HOME_ASSISTANT_ENTITY, dangling);
        assertEquals(dangling, parentOf(leak));

        for (String id : List.of(leak, battery, siren)) assertTrue(registry.setDiscoveredParent(id, "z2m-leak_alarm_bathroom"));

        assertEquals("z2m-leak_alarm_bathroom", parentOf(leak));
        // The leak sensor keeps its own type and enabled state: the link changes grouping only.
        assertEquals(DeviceType.WATER_LEAK_SENSOR, registry.get(leak).type());
        assertEquals(List.of("z2m-leak_alarm_bathroom", battery), ServiceHistory.idsFor("z2m-leak_alarm_bathroom", registry));
    }

    @Test
    void t5_theKiddeGroupBTemperatureFoldsIntoGroupAAndNothingIsLost() {
        String a = "ha-cabin-binary-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-co-alarm";
        String bTemp = "ha-cabin-sensor-kidde-air-quality-alarm-temperature";
        String bCo = "ha-cabin-binary-sensor-kidde-air-quality-alarm-carbon-monoxide-alarm";
        registry.registerDescriptor(new DeviceDescriptor(a, "Kidde CO alarm", DeviceType.CO_ALARM,
            Set.of(DeviceCapability.TELEMETRY), "ha_rest", "binary_sensor.kidde_co_alarm", true, "cabin"));
        entity(bTemp, "sensor.kidde_air_quality_alarm_temperature", DeviceType.TEMPERATURE_SENSOR, null);
        entity(bCo, "binary_sensor.kidde_air_quality_alarm_carbon_monoxide_alarm", DeviceType.HOME_ASSISTANT_ENTITY, a);

        assertTrue(registry.setDiscoveredParent(bTemp, a));

        assertEquals(List.of(a, bTemp), ServiceHistory.idsFor(a, registry));
        assertEquals(a, parentOf(bCo));
        // The B rows stay in the registry under their own ids: history remains readable under both.
        assertNotNull(registry.get(bTemp));
        assertNotNull(registry.get(bCo));
    }

    @Test
    void t6_aCreatedParentRowAdoptsItsExistingChildrenAndAddsExactlyOneRow() {
        String parent = "ha-cabin-binary-sensor-starlink-connectivity";
        String ping = "ha-cabin-sensor-starlink-ping";
        String stowed = "ha-cabin-switch-starlink-stowed";
        entity(ping, "sensor.starlink_ping", DeviceType.HOME_ASSISTANT_ENTITY, parent);
        entity(stowed, "switch.starlink_stowed", DeviceType.HOME_ASSISTANT_ENTITY, parent);
        assertNull(registry.get(parent));
        int before = registry.all().size();
        assertEquals(List.of(parent), ServiceHistory.idsFor(parent, registry));

        registry.registerDescriptor(new DeviceDescriptor(parent, "Starlink connectivity", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "ha_rest", "binary_sensor.starlink_connectivity", true, "cabin"));

        assertEquals(before + 1, registry.all().size());
        assertEquals(List.of(parent, ping), ServiceHistory.idsFor(parent, registry));
        assertEquals(parent, parentOf(ping));
        assertEquals(parent, parentOf(stowed));
    }

    @Test
    void t7_aFormerIdFoldsHistoryIntoTheCurrentParentAndNothingElseChanges() {
        String current = "z2m-0xa4c1381f3884ffff";
        String old = "z2m-Smart_Switch_Zigbee_|_Dehumidifier";
        zigbee(current, "0xa4c1381f3884ffff", DeviceType.HOME_ASSISTANT_ENTITY);
        int rows = registry.all().size();

        registry.saveConfiguration(current, null, true, Map.of("formerDeviceIds", List.of(old)));

        assertEquals(List.of(current, old), ServiceHistory.idsFor(current, registry));
        assertEquals(rows, registry.all().size());
        assertNull(registry.get(old));
        assertEquals("", parentOf(current));

        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put(current, List.of("power"));
        raw.put(old, List.of("energy", "power"));
        Map<String, List<String>> merged = ServiceHistory.mergeReportedFields(raw, registry);
        assertEquals(List.of(current), List.copyOf(merged.keySet()));
        assertEquals(List.of("power", "energy"), merged.get(current));

        // Clearing the attribute restores the original reads.
        registry.saveConfiguration(current, null, true, Map.of("formerDeviceIds", List.of()));
        assertEquals(List.of(current), ServiceHistory.idsFor(current, registry));
    }

    @Test
    void t7_aFormerIdCannotBeALiveRowItselfOrAClaimOfAnotherDevice() {
        zigbee("z2m-a", "a", DeviceType.TEMPERATURE_SENSOR);
        zigbee("z2m-b", "b", DeviceType.TEMPERATURE_SENSOR);

        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of("z2m-b"))));
        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of("z2m-a"))));
        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of(" "))));
        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of("old-1", "old-1"))));
        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", "old-1")));

        registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of("old-1")));
        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-b", null, true, Map.of("formerDeviceIds", List.of("old-1"))));
    }

    @Test
    void t7_aStaleFormerIdThatBecameALiveRowIsNeverFoldedIn() {
        zigbee("z2m-a", "a", DeviceType.TEMPERATURE_SENSOR);
        registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of("old-1")));
        zigbee("old-1", "old_one", DeviceType.TEMPERATURE_SENSOR);

        assertEquals(List.of("z2m-a"), ServiceHistory.idsFor("z2m-a", registry));
    }

    @Test
    void t7_aFormerOnlyFieldFoldsOntoTheOwnerEvenWhenTheOwnerLoggedNothing() {
        zigbee("z2m-a", "a", DeviceType.TEMPERATURE_SENSOR);
        registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of("old-1")));

        Map<String, List<String>> merged = ServiceHistory.mergeReportedFields(Map.of("old-1", List.of("energy")), registry);

        assertEquals(Map.of("z2m-a", List.of("energy")), merged);
    }

    @Test
    void t7_aDisabledOwnerLeavesTheFormerPickerEntryWhereItIs() {
        zigbee("z2m-a", "a", DeviceType.TEMPERATURE_SENSOR);
        registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of("old-1")));
        registry.saveConfiguration("z2m-a", null, false, Map.of());
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("z2m-a", List.of("power"));
        raw.put("old-1", List.of("energy"));

        Map<String, List<String>> merged = ServiceHistory.mergeReportedFields(raw, registry);

        assertEquals(List.of("power"), merged.get("z2m-a"));
        assertEquals(List.of("energy"), merged.get("old-1"));
    }

    @Test
    void t7_aNonStringFormerIdIsRejected() {
        zigbee("z2m-a", "a", DeviceType.TEMPERATURE_SENSOR);
        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of(123))));
        assertThrows(IllegalArgumentException.class,
            () -> registry.saveConfiguration("z2m-a", null, true, Map.of("formerDeviceIds", List.of(true))));
    }
}
