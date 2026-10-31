package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.api.DeviceController;
import com.cabin.orchestrator.api.HaServiceController;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfigService;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
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
import java.util.Optional;
import java.util.Set;
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

    /** A descriptor consistent with the status (the entity id is the connection string), so the older cases test the status fields. */
    private static Optional<String> deny(DeviceStatus status, String command) {
        Object entity = status.attributes().get("entityId");
        return DeviceCommandGuard.denial(descriptor(status.deviceId(), status.name(), status.type(), entity == null ? "switch.x" : String.valueOf(entity), "ha_rest"),
            status, command, null);
    }

    private static DeviceDescriptor descriptor(String id, String name, DeviceType type, String connection, String adapter) {
        return new DeviceDescriptor(id, name, type, Set.of(), adapter, connection, true, "cabin");
    }

    @Test
    void aValveTakesNoCommandFromTheDashboardWhicheverFieldNamesIt() {
        DeviceStatus byId = device("z2m-main_water_valve", "Main shutoff", DeviceType.HOME_ASSISTANT_ENTITY, null);
        DeviceStatus byName = device("ha-cabin-switch-x", "Main Water Valve", DeviceType.HOME_ASSISTANT_ENTITY, null);
        DeviceStatus byEntity = device("ha-cabin-switch-y", "Shutoff", DeviceType.HOME_ASSISTANT_ENTITY, "switch.main_water_valve");
        for (DeviceStatus valve : List.of(byId, byName, byEntity)) {
            for (String command : List.of("switch.turn_on", "switch.turn_off", "cover.open_cover", "cover.close_cover", "valve.open_valve")) {
                assertTrue(deny(valve, command).isPresent(), valve.deviceId() + " " + command);
            }
        }
    }

    @Test
    void aLockOnlyAcceptsLockLockNeverAnUnlockOrAnythingElse() {
        DeviceStatus lock = device("lock_front", "Front Door Lock", DeviceType.LOCK, "lock.front_door");
        assertTrue(deny(lock, "lock.lock").isEmpty(), "locking is the secure direction");
        for (String command : List.of("lock.unlock", "lock.open", "switch.turn_off", "unlock", "LOCK.LOCK", "null", "")) {
            assertTrue(deny(lock, command).isPresent(), command);
        }
    }

    @Test
    void anythingElseNamedLockIsDeniedLikeOnTheOtherPaths() {
        assertTrue(deny(device("ha-cabin-switch-z", "Garage Door Lock Relay", DeviceType.HOME_ASSISTANT_ENTITY, "switch.garage_lock"), "switch.turn_off").isPresent());
        assertTrue(deny(device("ha-cabin-switch-q", "Kitchen Clock", DeviceType.HOME_ASSISTANT_ENTITY, null), "switch.turn_off").isPresent(),
            "over-denies a name that merely contains the letters, the same as the other paths do");
    }

    @Test
    void anOrdinaryDeviceAndAnUnknownDeviceAreLeftToTheRegistry() {
        assertTrue(deny(device("z2m-heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY, "switch.heater_mech_room"), "switch.turn_on").isEmpty());
        assertTrue(DeviceCommandGuard.denial(null, null, "switch.turn_on", null).isEmpty());
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
        when(registry.descriptor("z2m-main_water_valve")).thenReturn(Optional.of(descriptor("z2m-main_water_valve", "Main water valve", DeviceType.HOME_ASSISTANT_ENTITY, "switch.main_water_valve", "ha_rest")));
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
        when(registry.descriptor("lock_front")).thenReturn(Optional.of(descriptor("lock_front", "Front Door Lock", DeviceType.LOCK, "lock.front_door", "ha_rest")));
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
        when(registry.descriptor("z2m-heater_mech_room")).thenReturn(Optional.of(descriptor("z2m-heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY, "switch.heater_mech_room", "ha_rest")));
        when(registry.sendCommand("z2m-heater_mech_room", "switch.turn_on", null)).thenReturn(true);

        ResponseEntity<Map<String, Object>> res = controller.sendCommand("z2m-heater_mech_room", Map.of("command", "switch.turn_on"));

        assertEquals(200, res.getStatusCode().value());
        assertEquals(true, res.getBody().get("accepted"));
        verify(registry).sendCommand("z2m-heater_mech_room", "switch.turn_on", null);
    }

    // ---- the connection string and the lock payload are checked as well as the labels ----

    @Test
    void theConnectionStringIsCheckedAsWellAsTheLabels() {
        DeviceStatus status = device("ha-cabin-switch-7", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, null);
        DeviceDescriptor descriptor = descriptor("ha-cabin-switch-7", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, "switch.main_water_valve", "ha_rest");
        for (String command : List.of("switch.turn_on", "switch.turn_off")) {
            assertTrue(DeviceCommandGuard.denial(descriptor, status, command, null).isPresent(), command);
        }
        DeviceDescriptor mqtt = descriptor("z2m-plug-9", "Plug 9", DeviceType.HOME_ASSISTANT_ENTITY, "zigbee2mqtt/main_water_valve", "mqtt");
        assertTrue(DeviceCommandGuard.denial(mqtt, device("z2m-plug-9", "Plug 9", DeviceType.HOME_ASSISTANT_ENTITY, null), "switch.turn_on", Map.of("state", "ON")).isPresent());
    }

    @Test
    void theCommandEndpointChecksTheConnectionString() {
        when(registry.get("ha-cabin-switch-7")).thenReturn(device("ha-cabin-switch-7", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, null));
        when(registry.descriptor("ha-cabin-switch-7")).thenReturn(Optional.of(descriptor("ha-cabin-switch-7", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, "switch.main_water_valve", "ha_rest")));
        when(registry.sendCommand(anyString(), anyString(), any())).thenReturn(true);

        assertEquals(403, controller.sendCommand("ha-cabin-switch-7", Map.of("command", "switch.turn_on")).getStatusCode().value());
        verify(registry, never()).sendCommand(anyString(), anyString(), any());
    }

    @Test
    void aDeviceWithADescriptorButNoStatusFailsClosed() {
        when(registry.get("ghost")).thenReturn(null);
        when(registry.descriptor("ghost")).thenReturn(Optional.of(descriptor("ghost", "Ghost", DeviceType.HOME_ASSISTANT_ENTITY, "switch.main_water_valve", "ha_rest")));
        when(registry.sendCommand(anyString(), anyString(), any())).thenReturn(true);

        assertEquals(403, controller.sendCommand("ghost", Map.of("command", "switch.turn_on")).getStatusCode().value());
        verify(registry, never()).sendCommand(anyString(), anyString(), any());
        assertTrue(DeviceCommandGuard.denial(null, device("x", "x", DeviceType.HOME_ASSISTANT_ENTITY, null), "switch.turn_on", null).isPresent(), "status without descriptor");
    }

    @Test
    void anMqttLockAcceptsOnlyTheLockPayloadNeverAnUnlockInsideIt() {
        DeviceStatus status = device("z2m-door", "Front door", DeviceType.LOCK, null);
        DeviceDescriptor mqtt = descriptor("z2m-door", "Front door", DeviceType.LOCK, "zigbee2mqtt/front_door", "mqtt");
        assertTrue(DeviceCommandGuard.denial(mqtt, status, "lock.lock", Map.of("state", "LOCK")).isEmpty());
        for (Object payload : List.of(Map.of("state", "UNLOCK"), Map.of("state", "lock"), Map.of("state", "LOCK", "extra", 1), Map.of(), "UNLOCK", List.of("LOCK"))) {
            assertTrue(DeviceCommandGuard.denial(mqtt, status, "lock.lock", payload).isPresent(), String.valueOf(payload));
        }
    }

    @Test
    void aHomeAssistantLockCarriesNoPayloadAtAll() {
        DeviceStatus status = device("lock_front", "Front Door Lock", DeviceType.LOCK, "lock.front_door");
        DeviceDescriptor ha = descriptor("lock_front", "Front Door Lock", DeviceType.LOCK, "lock.front_door", "ha_rest");
        assertTrue(DeviceCommandGuard.denial(ha, status, "lock.lock", null).isEmpty());
        assertTrue(DeviceCommandGuard.denial(ha, status, "lock.lock", Map.of("state", "LOCK")).isPresent());
        assertTrue(DeviceCommandGuard.denial(ha, status, "lock.lock", Map.of("code", "1234")).isPresent());
    }

    @Test
    void theCommandEndpointRefusesAnMqttLockCommandWithAnUnlockPayload() {
        when(registry.get("z2m-door")).thenReturn(device("z2m-door", "Front door", DeviceType.LOCK, null));
        when(registry.descriptor("z2m-door")).thenReturn(Optional.of(descriptor("z2m-door", "Front door", DeviceType.LOCK, "zigbee2mqtt/front_door", "mqtt")));
        when(registry.sendCommand(anyString(), anyString(), any())).thenReturn(true);

        assertEquals(403, controller.sendCommand("z2m-door", Map.of("command", "lock.lock", "payload", Map.of("state", "UNLOCK"))).getStatusCode().value());
        verify(registry, never()).sendCommand(anyString(), anyString(), any());
    }

    // ---- the guard follows the devices a command reaches ----

    private static DeviceCommandGuard.Reached reached(String id, String name, DeviceType type, DeviceType inferred, Set<DeviceCapability> caps) {
        return new DeviceCommandGuard.Reached(id, descriptor(id, name, type, "zigbee2mqtt/" + id, "mqtt"),
            device(id, name, type, null), inferred, caps);
    }

    @Test
    void aCommandThatReachesAValveByAnotherIdentifierIsRefused() {
        DeviceDescriptor neutral = descriptor("plug-9", "Porch plug", DeviceType.POWER_METER, "zigbee2mqtt/0x00158d0001abcdef", "mqtt");
        DeviceStatus status = device("plug-9", "Porch plug", DeviceType.POWER_METER, null);
        DeviceCommandGuard.Resolution reachesValve = new DeviceCommandGuard.Resolution(List.of(
            reached("plug-9", "Porch plug", DeviceType.POWER_METER, null, null),
            reached("z2m-0x00158d0001abcdef", "Shutoff", DeviceType.WATER_VALVE, DeviceType.WATER_VALVE, null)), false);

        assertTrue(DeviceCommandGuard.denial(neutral, status, "state.set", Map.of("state", "ON"), reachesValve).isPresent());
        // without a resolution only the labels on the target are judged
        assertTrue(DeviceCommandGuard.denial(neutral, status, "state.set", Map.of("state", "ON")).isEmpty());
    }

    @Test
    void anMqttTargetThatResolvesToNoKnownDeviceIsRefused() {
        DeviceDescriptor neutral = descriptor("plug-9", "Porch plug", DeviceType.POWER_METER, "zigbee2mqtt/0x00158d0001ffffff", "mqtt");
        assertTrue(DeviceCommandGuard.denial(neutral, device("plug-9", "Porch plug", DeviceType.POWER_METER, null), "state.set",
            Map.of("state", "ON"), new DeviceCommandGuard.Resolution(List.of(), true)).isPresent());
    }

    @Test
    void aLockStaysALockWhateverItsRegisteredTypeSays() {
        DeviceDescriptor recorded = descriptor("z2m-hall", "Hall plug", DeviceType.POWER_METER, "zigbee2mqtt/hall", "mqtt");
        DeviceStatus status = device("z2m-hall", "Hall plug", DeviceType.POWER_METER, null);
        DeviceCommandGuard.Resolution resolution = new DeviceCommandGuard.Resolution(List.of(
            reached("z2m-hall", "Hall plug", DeviceType.POWER_METER, DeviceType.LOCK, Set.of(DeviceCapability.ACCESS_CONTROL))), false);

        assertTrue(DeviceCommandGuard.denial(recorded, status, "state.set", Map.of("state", "UNLOCK"), resolution).isPresent(), "unlock command");
        assertTrue(DeviceCommandGuard.denial(recorded, status, "lock.lock", Map.of("state", "UNLOCK"), resolution).isPresent(), "unlock payload");
        assertTrue(DeviceCommandGuard.denial(recorded, status, "lock.lock", Map.of("state", "LOCK"), resolution).isEmpty(), "locking stays possible");
    }

    @Test
    void aValveTypeIsRefusedWhateverItsLabelsSay() {
        DeviceDescriptor valve = descriptor("ha-1", "Main shutoff", DeviceType.WATER_VALVE, "switch.main_shutoff", "ha_rest");
        assertTrue(DeviceCommandGuard.denial(valve, device("ha-1", "Main shutoff", DeviceType.WATER_VALVE, null), "switch.turn_on", null).isPresent());
        assertTrue(DeviceCommandGuard.isValveOrLock(valve, null));
    }

    @Test
    void theCommandEndpointRefusesACommandThatReachesAValve() {
        Zigbee2MqttAdapter z2m = mock(Zigbee2MqttAdapter.class);
        DeviceRegistry reg = mock(DeviceRegistry.class);
        DeviceController c = new DeviceController(reg, z2m, mock(MqttBridgeService.class), mock(DeviceHealthMonitor.class),
            mock(DeviceDisplayConfigService.class), mock(JdbcDeviceLifecycleVocabularyStore.class),
            mock(DeviceReportingRelationshipRepository.class), mock(DeviceRepository.class));
        DeviceDescriptor neutral = descriptor("plug-9", "Porch plug", DeviceType.POWER_METER, "zigbee2mqtt/0x00158d0001abcdef", "mqtt");
        DeviceDescriptor valve = descriptor("z2m-main_water_valve", "Shutoff", DeviceType.HOME_ASSISTANT_ENTITY, "zigbee2mqtt/main_water_valve", "mqtt");
        when(reg.descriptor("plug-9")).thenReturn(Optional.of(neutral));
        when(reg.get("plug-9")).thenReturn(device("plug-9", "Porch plug", DeviceType.POWER_METER, null));
        when(reg.descriptor("z2m-main_water_valve")).thenReturn(Optional.of(valve));
        when(reg.get("z2m-main_water_valve")).thenReturn(device("z2m-main_water_valve", "Shutoff", DeviceType.HOME_ASSISTANT_ENTITY, null));
        when(z2m.deviceIdsReachedBy("zigbee2mqtt/0x00158d0001abcdef")).thenReturn(Set.of("z2m-main_water_valve"));
        when(z2m.inferredShape(anyString())).thenReturn(Optional.empty());
        when(reg.sendCommand(anyString(), anyString(), any())).thenReturn(true);

        assertEquals(403, c.sendCommand("plug-9", Map.of("command", "state.set", "payload", Map.of("state", "ON"))).getStatusCode().value());
        verify(reg, never()).sendCommand(anyString(), anyString(), any());
    }

    @Test
    void theCommandEndpointRefusesALockCommandWithAnUnlockPayload() {
        Zigbee2MqttAdapter z2m = mock(Zigbee2MqttAdapter.class);
        DeviceRegistry reg = mock(DeviceRegistry.class);
        DeviceController c = new DeviceController(reg, z2m, mock(MqttBridgeService.class), mock(DeviceHealthMonitor.class),
            mock(DeviceDisplayConfigService.class), mock(JdbcDeviceLifecycleVocabularyStore.class),
            mock(DeviceReportingRelationshipRepository.class), mock(DeviceRepository.class));
        DeviceDescriptor recorded = descriptor("z2m-hall", "Hall plug", DeviceType.POWER_METER, "zigbee2mqtt/hall", "mqtt");
        when(reg.descriptor("z2m-hall")).thenReturn(Optional.of(recorded));
        when(reg.get("z2m-hall")).thenReturn(device("z2m-hall", "Hall plug", DeviceType.POWER_METER, null));
        when(z2m.deviceIdsReachedBy("zigbee2mqtt/hall")).thenReturn(Set.of("z2m-hall"));
        when(z2m.inferredShape("z2m-hall")).thenReturn(Optional.of(new Zigbee2MqttAdapter.InferredShape(DeviceType.LOCK, Set.of(DeviceCapability.ACCESS_CONTROL))));
        when(reg.sendCommand(anyString(), anyString(), any())).thenReturn(true);

        assertEquals(403, c.sendCommand("z2m-hall", Map.of("command", "lock.lock", "payload", Map.of("state", "UNLOCK"))).getStatusCode().value());
        verify(reg, never()).sendCommand(anyString(), anyString(), any());
    }

    // ---- Home Assistant targets: one entity, its own domain, an allowed domain ----

    @Test
    void aHomeAssistantDeviceMustNameOneEntityAndACommandInThatDomain() {
        DeviceStatus s = device("ha-9", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, null);
        for (String connection : List.of("all", "ALL", "group.living_room", "switch.a,switch.b", "switch.*", "switch", "", "Switch.Porch", "switch.porch extra")) {
            DeviceDescriptor d = descriptor("ha-9", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, connection, "ha_rest");
            assertTrue(DeviceCommandGuard.denial(d, s, "switch.turn_on", null).isPresent(), connection);
            assertTrue(DeviceCommandGuard.denial(d, s, "homeassistant.turn_on", null).isPresent(), connection);
        }
        DeviceDescriptor sw = descriptor("ha-9", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, "switch.porch", "ha_rest");
        assertTrue(DeviceCommandGuard.denial(sw, s, "switch.turn_on", null).isEmpty(), "the normal case still works");
        for (String command : List.of("lock.unlock", "homeassistant.turn_on", "script.turn_on", "light.turn_on", "switch", "switch.turn_on.extra", "SWITCH.TURN_ON", "")) {
            assertTrue(DeviceCommandGuard.denial(sw, s, command, null).isPresent(), "cross-domain or malformed: " + command);
        }
    }

    @Test
    void scriptsScenesAndAutomationsAreNotReachableFromTheDashboardCommandRoute() {
        DeviceStatus s = device("ha-9", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, null);
        for (String entity : List.of("script.goodnight", "scene.movie", "automation.leak_response", "group.all_lights", "homeassistant.restart")) {
            DeviceDescriptor d = descriptor("ha-9", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, entity, "ha_rest");
            String domain = entity.substring(0, entity.indexOf('.'));
            assertTrue(DeviceCommandGuard.denial(d, s, domain + ".turn_on", null).isPresent(), entity);
        }
    }

    // ---- pins for the three protections that must survive a mutation ----

    @Test
    void aDeviceSharingTheTargetsConnectionStringIsJudgedToo() {
        DeviceRegistry reg = mock(DeviceRegistry.class);
        DeviceController c = new DeviceController(reg, mock(Zigbee2MqttAdapter.class), mock(MqttBridgeService.class), mock(DeviceHealthMonitor.class),
            mock(DeviceDisplayConfigService.class), mock(JdbcDeviceLifecycleVocabularyStore.class),
            mock(DeviceReportingRelationshipRepository.class), mock(DeviceRepository.class));
        DeviceDescriptor neutral = descriptor("ha-1", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, "switch.shared_x", "ha_rest");
        DeviceDescriptor sibling = descriptor("ha-2", "Plug 2", DeviceType.WATER_VALVE, "switch.shared_x", "ha_rest");
        when(reg.descriptor("ha-1")).thenReturn(Optional.of(neutral));
        when(reg.get("ha-1")).thenReturn(device("ha-1", "Porch plug", DeviceType.HOME_ASSISTANT_ENTITY, null));
        when(reg.descriptor("ha-2")).thenReturn(Optional.of(sibling));
        when(reg.get("ha-2")).thenReturn(device("ha-2", "Plug 2", DeviceType.WATER_VALVE, null));
        when(reg.descriptorsByConnectionString("switch.shared_x")).thenReturn(List.of(neutral, sibling));
        when(reg.sendCommand(anyString(), anyString(), any())).thenReturn(true);

        assertEquals(403, c.sendCommand("ha-1", Map.of("command", "switch.turn_on")).getStatusCode().value());
        verify(reg, never()).sendCommand(anyString(), anyString(), any());
    }

    @Test
    void anInferredLockTypeAloneMakesTheTargetALock() {
        DeviceDescriptor edited = descriptor("z2m-hall", "Hall plug", DeviceType.POWER_METER, "zigbee2mqtt/hall", "mqtt");
        DeviceStatus status = device("z2m-hall", "Hall plug", DeviceType.POWER_METER, null);
        DeviceCommandGuard.Resolution inferredType = new DeviceCommandGuard.Resolution(List.of(
            reached("z2m-hall", "Hall plug", DeviceType.POWER_METER, DeviceType.LOCK, null)), false);
        assertTrue(DeviceCommandGuard.denial(edited, status, "state.set", Map.of("state", "UNLOCK"), inferredType).isPresent());
        assertTrue(DeviceCommandGuard.denial(edited, status, "lock.lock", Map.of("state", "UNLOCK"), inferredType).isPresent());
        assertTrue(DeviceCommandGuard.denial(edited, status, "lock.lock", Map.of("state", "LOCK"), inferredType).isEmpty());
    }

    @Test
    void anInferredAccessControlCapabilityAloneMakesTheTargetALock() {
        DeviceDescriptor edited = descriptor("z2m-hall", "Hall plug", DeviceType.POWER_METER, "zigbee2mqtt/hall", "mqtt");
        DeviceStatus status = device("z2m-hall", "Hall plug", DeviceType.POWER_METER, null);
        DeviceCommandGuard.Resolution inferredCaps = new DeviceCommandGuard.Resolution(List.of(
            reached("z2m-hall", "Hall plug", DeviceType.POWER_METER, null, Set.of(DeviceCapability.ACCESS_CONTROL))), false);
        assertTrue(DeviceCommandGuard.denial(edited, status, "state.set", Map.of("state", "UNLOCK"), inferredCaps).isPresent());
        assertTrue(DeviceCommandGuard.denial(edited, status, "lock.lock", Map.of("state", "UNLOCK"), inferredCaps).isPresent());
        assertTrue(DeviceCommandGuard.denial(edited, status, "lock.lock", Map.of("state", "LOCK"), inferredCaps).isEmpty());
    }
}
