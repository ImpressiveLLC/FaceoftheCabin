package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.api.DeviceController;
import com.cabin.orchestrator.api.HaServiceController;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfigService;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.mqtt.MqttBridgeService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Safety order 2026-10-31: the valve/lock denial covers the device-details command path too, not only the row button and
 * /api/ha/services. No Docker needed: the controller is built over mocks.
 */
class DeviceCommandGuardTest {

    private static DeviceStatus device(String id, String name, DeviceType type, String entityId) {
        Map<String, Object> attrs = new HashMap<>();
        if (entityId != null) attrs.put("entityId", entityId);
        return new DeviceStatus(id, type, name, "ONLINE", Instant.parse("2026-10-31T12:00:00Z"), attrs, "cabin");
    }

    @Test
    void aValveTakesNoCommandFromTheDashboardWhicheverFieldNamesIt() {
        DeviceStatus byId = device("z2m-main_water_valve", "Main shutoff", DeviceType.HOME_ASSISTANT_ENTITY, null);
        DeviceStatus byName = device("ha-cabin-switch-x", "Main Water Valve", DeviceType.HOME_ASSISTANT_ENTITY, null);
        DeviceStatus byEntity = device("ha-cabin-switch-y", "Shutoff", DeviceType.HOME_ASSISTANT_ENTITY, "switch.main_water_valve");
        for (DeviceStatus valve : List.of(byId, byName, byEntity)) {
            for (String command : List.of("switch.turn_on", "switch.turn_off", "cover.open_cover", "cover.close_cover", "valve.open_valve")) {
                assertTrue(DeviceCommandGuard.denial(valve, command).isPresent(), valve.deviceId() + " " + command);
            }
        }
    }

    @Test
    void aLockOnlyAcceptsLockLockNeverAnUnlockOrAnythingElse() {
        DeviceStatus lock = device("lock_front", "Front Door Lock", DeviceType.LOCK, "lock.front_door");
        assertTrue(DeviceCommandGuard.denial(lock, "lock.lock").isEmpty(), "locking is the secure direction");
        for (String command : List.of("lock.unlock", "lock.open", "switch.turn_off", "unlock", "LOCK.LOCK", "null", "")) {
            assertTrue(DeviceCommandGuard.denial(lock, command).isPresent(), command);
        }
    }

    @Test
    void anythingElseNamedLockIsDeniedLikeOnTheOtherPaths() {
        assertTrue(DeviceCommandGuard.denial(device("ha-cabin-switch-z", "Garage Door Lock Relay", DeviceType.HOME_ASSISTANT_ENTITY, "switch.garage_lock"), "switch.turn_off").isPresent());
        assertTrue(DeviceCommandGuard.denial(device("ha-cabin-switch-q", "Kitchen Clock", DeviceType.HOME_ASSISTANT_ENTITY, null), "switch.turn_off").isPresent(),
            "over-denies a name that merely contains the letters, the same as the other paths do");
    }

    @Test
    void anOrdinaryDeviceAndAnUnknownDeviceAreLeftToTheRegistry() {
        assertTrue(DeviceCommandGuard.denial(device("z2m-heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY, "switch.heater_mech_room"), "switch.turn_on").isEmpty());
        assertTrue(DeviceCommandGuard.denial(null, "switch.turn_on").isEmpty());
    }

    @Test
    void theHaServicesPathUsesTheSameConstant() throws Exception {
        Field f = HaServiceController.class.getDeclaredField("DENIED_ENTITY");
        f.setAccessible(true);
        assertSame(DeviceCommandGuard.DENIED_ENTITY, f.get(null), "one constant, so the two paths cannot disagree");
        assertTrue(((Pattern) f.get(null)).matcher("switch.main_water_valve").find());
    }

    // ── POST /api/devices/{id}/command ───────────────────────────────────────

    private final DeviceRegistry registry = mock(DeviceRegistry.class);
    private final DeviceController controller = new DeviceController(registry, mock(Zigbee2MqttAdapter.class),
        mock(MqttBridgeService.class), mock(DeviceHealthMonitor.class), mock(DeviceDisplayConfigService.class),
        mock(JdbcDeviceLifecycleVocabularyStore.class), mock(DeviceReportingRelationshipRepository.class), mock(DeviceRepository.class));

    @Test
    void theCommandEndpointRefusesTheValveWith403AndNeverReachesTheRegistry() {
        when(registry.get("z2m-main_water_valve")).thenReturn(device("z2m-main_water_valve", "Main water valve", DeviceType.HOME_ASSISTANT_ENTITY, "switch.main_water_valve"));
        when(registry.sendCommand(anyString(), anyString(), any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.sendCommand("z2m-main_water_valve", Map.of("command", "switch.turn_on"));

        assertEquals(403, res.getStatusCode().value());
        assertEquals(false, res.getBody().get("accepted"));
        assertNotNull(res.getBody().get("error"));
        verify(registry, never()).sendCommand(anyString(), anyString(), any());
    }

    @Test
    void theCommandEndpointRefusesAnUnlockButStillLocks() {
        when(registry.get("lock_front")).thenReturn(device("lock_front", "Front Door Lock", DeviceType.LOCK, "lock.front_door"));
        when(registry.sendCommand("lock_front", "lock.lock", null)).thenReturn(true);

        assertEquals(403, controller.sendCommand("lock_front", Map.of("command", "lock.unlock")).getStatusCode().value());
        verify(registry, never()).sendCommand("lock_front", "lock.unlock", null);
        ResponseEntity<Map<String, Object>> locked = controller.sendCommand("lock_front", Map.of("command", "lock.lock"));
        assertEquals(200, locked.getStatusCode().value());
        assertEquals(true, locked.getBody().get("accepted"));
    }

    @Test
    void anOrdinaryDeviceStillGetsItsCommand() {
        when(registry.get("z2m-heater_mech_room")).thenReturn(device("z2m-heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY, "switch.heater_mech_room"));
        when(registry.sendCommand("z2m-heater_mech_room", "switch.turn_on", null)).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.sendCommand("z2m-heater_mech_room", Map.of("command", "switch.turn_on"));

        assertEquals(200, res.getStatusCode().value());
        assertEquals(true, res.getBody().get("accepted"));
        verify(registry).sendCommand("z2m-heater_mech_room", "switch.turn_on", null);
    }
}
