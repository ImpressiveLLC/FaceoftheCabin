package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** W-93 apply and rollback: administrator only, per-field, never touches name/enabled/location, undoable. */
class DeviceRedetectControllerTest {

    private static final String S61 = """
        [{"friendly_name":"s61","ieee_address":"0xa4c1381f3884ffff",
          "definition":{"model":"S61SZBTPB","vendor":"SONOFF","description":"Smart plug: experimental state and metering adapter","exposes":[
           {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7}]},
           {"type":"numeric","name":"power","property":"power","access":5}]}}]
        """;

    private DeviceRegistry registry;
    private DeviceRedetectController controller;

    @BeforeEach
    void setUp() throws Exception {
        registry = new DeviceRegistry(List.of());
        Zigbee2MqttAdapter adapter = new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry());
        // A configured (assigned) row registered under the old, wrong shape, as the live S61 was.
        registry.registerConfiguredDevice(new DeviceDescriptor("z2m-s61", "Dehumidifier switch",
            DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.POWER_MONITOR, DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/s61", true, "cabin"));
        adapter.messageArrived("zigbee2mqtt/bridge/devices", new MqttMessage(S61.getBytes()));
        controller = new DeviceRedetectController(registry, adapter, new EventPublisher());
    }

    private static MockHttpServletRequest as(HouseholdRole role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (role != null) r.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return r;
    }

    private static final MockHttpServletRequest ADMIN = as(HouseholdRole.ADMINISTRATOR);

    @Test
    void everyOtherRoleIsRefusedOnAllThreeRoutesAndNothingChanges() {
        for (HouseholdRole role : new HouseholdRole[] { null, HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD }) {
            assertEquals(HttpStatus.FORBIDDEN, controller.preview(as(role)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN,
                controller.apply("z2m-s61", Map.of("fields", List.of("type")), as(role)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.rollback("z2m-s61", null, as(role)).getStatusCode());
        }
        assertEquals(DeviceType.HOME_ASSISTANT_ENTITY, registry.descriptor("z2m-s61").orElseThrow().type());
    }

    @Test
    void previewIsADryRunThatChangesNothing() {
        ResponseEntity<?> r = controller.preview(ADMIN);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) r.getBody();
        assertEquals(true, body.get("dryRun"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) body.get("devices")).get(0);
        assertEquals(true, row.get("changed"));
        assertEquals(List.of("type", "capabilities"), row.get("changedFields"));
        assertEquals(Map.of("type", "POWER_METER", "capabilities", List.of("COMMAND", "POWER_MONITOR", "TELEMETRY")),
            row.get("proposed"));
        assertEquals(DeviceType.HOME_ASSISTANT_ENTITY, registry.descriptor("z2m-s61").orElseThrow().type());
    }

    @Test
    void applyChangesOnlyTheApprovedFieldsAndNeverNameEnabledOrLocation() {
        ResponseEntity<?> r = controller.apply("z2m-s61", Map.of("fields", List.of("type")), ADMIN);
        assertEquals(HttpStatus.OK, r.getStatusCode());
        DeviceDescriptor after = registry.descriptor("z2m-s61").orElseThrow();
        assertEquals(DeviceType.POWER_METER, after.type());
        assertEquals(Set.of(DeviceCapability.POWER_MONITOR, DeviceCapability.TELEMETRY), after.capabilities(),
            "capabilities were not approved, so they must stay");
        assertEquals("Dehumidifier switch", after.name());
        assertTrue(after.enabled());
        assertEquals("cabin", after.location());
        assertEquals("zigbee2mqtt/s61", after.connectionString());
    }

    @Test
    void applyRejectsEmptyOrUnknownFieldsAndUnknownDevices() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.apply("z2m-s61", Map.of(), ADMIN).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.apply("z2m-s61", Map.of("fields", List.of("name")), ADMIN).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST,
            controller.apply("z2m-s61", Map.of("fields", List.of("enabled")), ADMIN).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND,
            controller.apply("z2m-nope", Map.of("fields", List.of("type")), ADMIN).getStatusCode());
    }

    @Test
    void rollbackRestoresTheValuesFromTheLastApply() {
        controller.apply("z2m-s61", Map.of("fields", List.of("type", "capabilities")), ADMIN);
        assertEquals(DeviceType.POWER_METER, registry.descriptor("z2m-s61").orElseThrow().type());

        assertEquals(HttpStatus.OK, controller.rollback("z2m-s61", null, ADMIN).getStatusCode());
        DeviceDescriptor restored = registry.descriptor("z2m-s61").orElseThrow();
        assertEquals(DeviceType.HOME_ASSISTANT_ENTITY, restored.type());
        assertEquals(Set.of(DeviceCapability.POWER_MONITOR, DeviceCapability.TELEMETRY), restored.capabilities());
        assertEquals(HttpStatus.NOT_FOUND, controller.rollback("z2m-s61", null, ADMIN).getStatusCode(),
            "a second rollback has nothing left to restore");
    }

    @Test
    void rollbackCanUseTheObjectAnEarlierApplyReturnedAfterARestart() {
        ResponseEntity<?> applied = controller.apply("z2m-s61", Map.of("fields", List.of("type", "capabilities")), ADMIN);
        @SuppressWarnings("unchecked")
        Map<String, Object> rollbackObject = (Map<String, Object>) ((Map<?, ?>) applied.getBody()).get("rollback");

        DeviceRedetectController afterRestart = new DeviceRedetectController(registry, adapterOf(), new EventPublisher());
        assertEquals(HttpStatus.OK, afterRestart.rollback("z2m-s61", rollbackObject, ADMIN).getStatusCode());
        assertEquals(DeviceType.HOME_ASSISTANT_ENTITY, registry.descriptor("z2m-s61").orElseThrow().type());
    }

    @Test
    void anApplyThatChangesNothingSaysSoAndLeavesNoRollbackBehind() {
        controller.apply("z2m-s61", Map.of("fields", List.of("type", "capabilities")), ADMIN);
        ResponseEntity<?> again = controller.apply("z2m-s61", Map.of("fields", List.of("type", "capabilities")), ADMIN);
        assertEquals(false, ((Map<?, ?>) again.getBody()).get("changed"));
    }

    private Zigbee2MqttAdapter adapterOf() {
        try {
            Zigbee2MqttAdapter adapter = new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry());
            adapter.messageArrived("zigbee2mqtt/bridge/devices", new MqttMessage(S61.getBytes()));
            return adapter;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
