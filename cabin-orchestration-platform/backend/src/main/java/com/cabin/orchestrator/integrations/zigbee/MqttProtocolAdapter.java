package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Wraps Zigbee2MqttAdapter's own sendCommand(friendlyName, payload) behind
 * the generic ProtocolAdapter contract DeviceRegistry dispatches through.
 *
 * Without this class, DeviceRegistry.sendCommand() silently no-ops for
 * every Zigbee device -- Zigbee2MqttAdapter implements MqttCallback, not
 * ProtocolAdapter, so it was never in the `adapters` map DeviceRegistry
 * builds from Spring's List<ProtocolAdapter> injection. Confirmed live
 * 2026-08-14: main_water_valve's own descriptor already carries
 * protocolAdapter="mqtt", connectionString="zigbee2mqtt/main_water_valve"
 * -- this class is the missing other half of that existing contract, not
 * a new device-side change.
 *
 * Z2M's own protocol is a flat property-set payload on a `/set` topic, not
 * a named RPC call -- unlike HomeAssistantAdapter's domain.service command
 * strings, the `command` parameter here is advisory/logged only; the real
 * instruction is entirely in `payload`.
 */
@Component
public class MqttProtocolAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttProtocolAdapter.class);
    private static final String Z2M_PREFIX = "zigbee2mqtt/";

    private final Zigbee2MqttAdapter z2m;

    public MqttProtocolAdapter(Zigbee2MqttAdapter z2m) {
        this.z2m = z2m;
    }

    @Override
    public String adapterType() {
        return "mqtt";
    }

    /** Zigbee devices are push-only over MQTT -- no request/response poll exists. */
    @Override
    public Optional<DeviceStatus> fetchState(DeviceDescriptor descriptor) {
        return Optional.empty();
    }

    @Override
    public boolean sendCommand(DeviceDescriptor descriptor, String command, Object payload) {
        String connectionString = descriptor.connectionString();
        if (connectionString == null || !connectionString.startsWith(Z2M_PREFIX)) {
            log.warn("MqttProtocolAdapter cannot resolve a friendly name from connectionString={}", connectionString);
            return false;
        }
        if (!(payload instanceof Map<?, ?> rawPayload)) {
            log.warn("MqttProtocolAdapter requires a Map payload for {}, got {}",
                descriptor.deviceId(), payload == null ? "null" : payload.getClass());
            return false;
        }
        String friendlyName = connectionString.substring(Z2M_PREFIX.length());
        @SuppressWarnings("unchecked")
        Map<String, Object> z2mPayload = (Map<String, Object>) rawPayload;
        log.info("MqttProtocolAdapter: sending {} to {} (command hint: {})", z2mPayload, friendlyName, command);
        return z2m.sendCommand(friendlyName, z2mPayload);
    }
}
