package com.cabin.orchestrator.events;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-10-24: after W-88 made the Zigbee device the parent and stopped the linked Home Assistant sensor
 * entity logging a second series, the outside temperature chart ("Temp Outside | Lower Level Temperature",
 * logged under the HA entity's id) stopped at 2026-10-20 while the readings carried on under the Zigbee
 * parent. A parent's history and picker entry now include its linked entities.
 */
class ServiceHistoryTest {

    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        parent("z2m-temp_outside_lowest", "temp_outside_lowest", "cabin", true);
        child("ha-cabin-sensor-temp-outside-lowest-temperature", "sensor.temp_outside_lowest_temperature", "cabin",
            "z2m-temp_outside_lowest");
        child("ha-cabin-number-temp-outside-lowest-temperature-calibration", "number.temp_outside_lowest_temperature_calibration",
            "cabin", "z2m-temp_outside_lowest");
    }

    private void parent(String id, String friendly, String location, boolean enabled) {
        registry.registerDescriptor(new DeviceDescriptor(id, friendly, DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/" + friendly, enabled, location));
    }

    private void child(String id, String entityId, String location, String parentId) {
        registry.registerCandidate(new DeviceDescriptor(id, entityId, DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "ha_rest", entityId, false, location), Map.of("entityId", entityId));
        registry.setDiscoveredParent(id, parentId);
    }

    @Test
    void aZigbeeParentsHistoryIncludesItsLinkedSensorEntitiesButNotOtherServices() {
        assertEquals(List.of("z2m-temp_outside_lowest", "ha-cabin-sensor-temp-outside-lowest-temperature"),
            ServiceHistory.idsFor("z2m-temp_outside_lowest", registry));
    }

    @Test
    void aDeviceThatIsNotANativeZigbeeParentIsJustItself() {
        assertEquals(List.of("ha-cabin-sensor-temp-outside-lowest-temperature"),
            ServiceHistory.idsFor("ha-cabin-sensor-temp-outside-lowest-temperature", registry));
        assertEquals(List.of("unknown-id"), ServiceHistory.idsFor("unknown-id", registry));
    }

    @Test
    void theEntitysPickerEntryMovesOntoTheEnabledParentSoOneSeriesIsOffered() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("ha-cabin-sensor-temp-outside-lowest-temperature", List.of("temperature"));
        raw.put("z2m-temp_outside_lowest", List.of("battery", "temperature"));
        raw.put("z2m-temp_kitchen", List.of("temperature", "humidity"));

        Map<String, List<String>> merged = ServiceHistory.mergeReportedFields(raw, registry);

        assertFalse(merged.containsKey("ha-cabin-sensor-temp-outside-lowest-temperature"));
        assertEquals(List.of("battery", "temperature"), merged.get("z2m-temp_outside_lowest"));
        assertEquals(List.of("temperature", "humidity"), merged.get("z2m-temp_kitchen"));
    }

    @Test
    void anEntityWhoseParentNeverLoggedItselfStillAppearsUnderTheParent() {
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("ha-cabin-sensor-temp-outside-lowest-temperature", List.of("temperature"));

        Map<String, List<String>> merged = ServiceHistory.mergeReportedFields(raw, registry);

        assertEquals(List.of("temperature"), merged.get("z2m-temp_outside_lowest"));
        assertFalse(merged.containsKey("ha-cabin-sensor-temp-outside-lowest-temperature"));
    }

    @Test
    void aDisabledParentLeavesItsEntitiesAsTheyWereSoTheSeriesDoesNotVanish() {
        parent("z2m-off_box", "off_box", "cabin", false);
        child("ha-cabin-sensor-off-box-temperature", "sensor.off_box_temperature", "cabin", "z2m-off_box");
        Map<String, List<String>> raw = new LinkedHashMap<>();
        raw.put("ha-cabin-sensor-off-box-temperature", List.of("temperature"));

        Map<String, List<String>> merged = ServiceHistory.mergeReportedFields(raw, registry);

        assertEquals(List.of("temperature"), merged.get("ha-cabin-sensor-off-box-temperature"));
        assertFalse(merged.containsKey("z2m-off_box"));
    }
}
