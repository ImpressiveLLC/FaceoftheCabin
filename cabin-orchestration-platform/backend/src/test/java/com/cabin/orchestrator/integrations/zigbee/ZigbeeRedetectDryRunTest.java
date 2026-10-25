package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * W-93 dry run, read-only and offline: feeds a captured zigbee2mqtt/bridge/devices payload
 * through the adapter's own inference and prints, per Zigbee device, the type and capabilities
 * a person-approved re-detect would propose next to what the live registry holds today. It
 * changes nothing anywhere. Run with
 * {@code -Dredetect.bridge=<bridge-devices.json> -Dredetect.live=<live-shapes.json>
 * -Dredetect.out=<report.txt>}.
 */
@EnabledIfSystemProperty(named = "redetect.bridge", matches = ".+")
class ZigbeeRedetectDryRunTest {

    @Test
    void printsTheProposedReDetectDiff() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String bridge = Files.readString(Path.of(System.getProperty("redetect.bridge")));
        JsonNode live = mapper.readTree(Files.readString(Path.of(System.getProperty("redetect.live"))));
        DeviceRegistry registry = new DeviceRegistry(List.of());
        Zigbee2MqttAdapter adapter = new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry());
        adapter.messageArrived("zigbee2mqtt/bridge/devices", new MqttMessage(bridge.getBytes()));

        Map<String, JsonNode> liveById = new HashMap<>();
        live.forEach(n -> liveById.put(n.get("deviceId").asText(), n));
        StringBuilder out = new StringBuilder("deviceId | live type / capabilities | proposed type / capabilities | change\n");
        for (JsonNode device : mapper.readTree(bridge)) {
            String friendly = device.path("friendly_name").asText();
            if (friendly.equals("Coordinator")) continue;
            String ieee = device.path("ieee_address").asText();
            String proposedId = "z2m-" + friendly.replace(" ", "_");
            var proposed = registry.descriptor(proposedId).orElseThrow();
            JsonNode current = liveById.containsKey("z2m-" + ieee) ? liveById.get("z2m-" + ieee) : liveById.get(proposedId);
            List<String> proposedCaps = proposed.capabilities().stream().map(Enum::name).sorted().toList();
            String currentText = current == null ? "(no live row)"
                : current.get("type").asText() + " " + current.get("capabilities");
            String proposedText = proposed.type() + " " + proposedCaps;
            boolean same = current != null && current.get("type").asText().equals(proposed.type().name())
                && mapper.convertValue(current.get("capabilities"), List.class).equals(proposedCaps);
            out.append(current == null ? proposedId : current.get("deviceId").asText()).append(" | ")
               .append(currentText).append(" | ").append(proposedText).append(" | ")
               .append(same ? "no change" : "CHANGE").append('\n');
        }
        Files.writeString(Path.of(System.getProperty("redetect.out")), out.toString());
    }
}
