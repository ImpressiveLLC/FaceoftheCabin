package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Without MqttProtocolAdapter, DeviceRegistry.sendCommand() silently
 * no-ops for every Zigbee device -- Zigbee2MqttAdapter implements
 * MqttCallback, not ProtocolAdapter, so it was never in DeviceRegistry's
 * adapter map (confirmed live 2026-08-14 against main_water_valve).
 * These tests exercise the actual parsing/forwarding logic this class
 * adds, not the real MQTT publish itself -- RecordingZ2mAdapter overrides
 * sendCommand() to record the call instead of touching the (unconnected,
 * client==null in a plain unit test) real MQTT client.
 */
class MqttProtocolAdapterTest {

    private static class RecordingZ2mAdapter extends Zigbee2MqttAdapter {
        String lastFriendlyName;
        Map<String, Object> lastPayload;
        boolean returnValue = true;

        RecordingZ2mAdapter() {
            super(new DeviceRegistry(List.of()), new EventPublisher(), new SignalQualityRegistry());
        }

        @Override
        public boolean sendCommand(String friendlyName, Map<String, Object> payload) {
            lastFriendlyName = friendlyName;
            lastPayload = payload;
            return returnValue;
        }
    }

    private RecordingZ2mAdapter z2m;
    private MqttProtocolAdapter adapter;

    @BeforeEach
    void setUp() {
        z2m = new RecordingZ2mAdapter();
        adapter = new MqttProtocolAdapter(z2m);
    }

    @Test
    void adapterTypeIsMqtt() {
        assertEquals("mqtt", adapter.adapterType());
    }

    @Test
    void fetchStateIsAlwaysEmptyBecauseZigbeeDevicesArePushOnly() {
        assertEquals(Optional.empty(), adapter.fetchState(descriptor("zigbee2mqtt/main_water_valve")));
    }

    @Test
    void sendCommandStripsTheZ2mPrefixToRecoverTheFriendlyName() {
        boolean result = adapter.sendCommand(
            descriptor("zigbee2mqtt/main_water_valve"), "state.set", Map.of("state", "OFF"));

        assertTrue(result);
        assertEquals("main_water_valve", z2m.lastFriendlyName);
        assertEquals(Map.of("state", "OFF"), z2m.lastPayload);
    }

    @Test
    void sendCommandFailsClosedWhenConnectionStringIsNotAZ2mTopic() {
        boolean result = adapter.sendCommand(
            descriptor("ha_rest/switch.something"), "state.set", Map.of("state", "OFF"));

        assertFalse(result);
        assertNull(z2m.lastFriendlyName, "must never fall through to the real adapter with an unparseable connection string");
    }

    @Test
    void sendCommandFailsClosedWhenPayloadIsNotAMap() {
        boolean result = adapter.sendCommand(
            descriptor("zigbee2mqtt/main_water_valve"), "state.set", "not-a-map");

        assertFalse(result);
        assertNull(z2m.lastFriendlyName);
    }

    private DeviceDescriptor descriptor(String connectionString) {
        return new DeviceDescriptor(
            "z2m-main_water_valve", "Main Water Valve", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY),
            "mqtt", connectionString, true, "cabin");
    }
}
