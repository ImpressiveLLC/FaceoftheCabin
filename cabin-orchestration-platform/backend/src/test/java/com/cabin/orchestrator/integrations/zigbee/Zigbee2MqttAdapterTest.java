package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage for the 2026-08-08 SignalQualityRegistry wiring --
 * see that class's own comment for the full prototype reasoning. Not a
 * full test suite for Zigbee2MqttAdapter (that class had zero test
 * coverage before this and gaining it wholesale is a separate task);
 * this only covers the one new integration point this session added.
 */
class Zigbee2MqttAdapterTest {

    private DeviceRegistry registry;
    private SignalQualityRegistry signalQualityRegistry;
    private Zigbee2MqttAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        signalQualityRegistry = new SignalQualityRegistry();
        adapter = new Zigbee2MqttAdapter(registry, new EventPublisher(), signalQualityRegistry);
    }

    private void deliver(String topic, String payload) throws Exception {
        adapter.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    /** Matches real Z2M startup order: bridge/devices always arrives before any device state message. */
    private void registerDevice(String friendlyName) throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"%s","type":"EndDevice","definition":{
              "model":"SNZB-03PR2","description":"motion","vendor":"SONOFF","exposes":[]}}]
            """.formatted(friendlyName));
    }

    @Test
    void deviceStateWithLinkqualityIsRecordedInSignalQualityRegistry() throws Exception {
        registerDevice("motion_entry");

        deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 160, \"battery\": 100}");

        var a = signalQualityRegistry.assess("z2m-motion_entry");
        assertTrue(a.isPresent(), "a device message with linkquality must be recorded");
        assertEquals(160, a.get().current());
    }

    @Test
    void repeatedMessagesAccumulateHistoryNotJustTheLatestValue() throws Exception {
        registerDevice("motion_entry");

        for (int i = 0; i < 6; i++) {
            deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 200}");
        }

        var a = signalQualityRegistry.assess("z2m-motion_entry").orElseThrow();
        assertEquals(6, a.sampleCount(), "each message must add to history, not overwrite it");
    }

    @Test
    void aMessageWithoutLinkqualityIsNotRecorded() throws Exception {
        registerDevice("motion_entry");

        deliver("zigbee2mqtt/motion_entry", "{\"battery\": 100}");

        assertTrue(signalQualityRegistry.assess("z2m-motion_entry").isEmpty(),
            "a message with no linkquality field must not create a bogus reading");
    }

    @Test
    void anUnregisteredDevicesStateMessageIsIgnoredEntirely() throws Exception {
        // No registerDevice() call -- messageArrived's own routing requires
        // the friendly name to already be known (bridge/devices), matching
        // real Z2M behavior where bridge/devices always arrives first.
        deliver("zigbee2mqtt/never_registered", "{\"linkquality\": 160}");

        assertTrue(signalQualityRegistry.assess("z2m-never_registered").isEmpty());
    }

    @Test
    void repeatedBridgeListCorrectsStaleCandidateMetadata() throws Exception {
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-temp_kitchen", "main_water_valve", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND), "mqtt", "zigbee2mqtt/wrong", false, "cabin"),
            Map.of("model", "wrong"));

        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"temp_kitchen","type":"EndDevice","definition":{
              "model":"SNZB-02D","description":"temperature sensor","vendor":"SONOFF",
              "exposes":[{"type":"numeric","property":"temperature","access":1}]}}]
            """);

        var descriptor = registry.descriptor("z2m-temp_kitchen").orElseThrow();
        var status = registry.get("z2m-temp_kitchen");
        assertEquals("temp_kitchen", descriptor.name());
        assertEquals(DeviceType.TEMPERATURE_SENSOR, descriptor.type());
        assertEquals("zigbee2mqtt/temp_kitchen", descriptor.connectionString());
        assertEquals("temp_kitchen", status.name());
        assertEquals(DeviceType.TEMPERATURE_SENSOR, status.type());
        assertEquals("SNZB-02D", status.attributes().get("model"));
        assertEquals(true, status.attributes().get("candidate"));
    }
}
