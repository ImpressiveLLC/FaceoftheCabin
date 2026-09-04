package com.cabin.orchestrator.platforminfo;

import com.cabin.orchestrator.helpdesk.OllamaClient;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bug #5 (2026-09 bug sprint) -- backend for the admin-only Platform config
 * panel: live-fetched integration versions where a version is actually
 * exposed, a static hardware catalog (this platform is a management layer
 * over physical, human-installed hardware -- there is no API to discover a
 * Zigbee coordinator model or a camera brand), and an explicit AI-inference
 * disclosure. The disclosure is not a formality -- household members should
 * be able to see, without asking, that any AI inference this platform does
 * (Tiny Helpdesk) runs on hardware physically in the cabin, reachable only
 * over Tailscale, and never leaves the property.
 *
 * Every live lookup here is on-demand (called only when this endpoint is
 * hit), never scheduled/polled -- matching this codebase's existing
 * "unconfigured or unreachable degrades gracefully, never a 500" convention
 * (OllamaHttpClient, HomeAssistantAdapter.fetchState()).
 */
@Service
public class PlatformInfoService {

    private final HomeAssistantAdapter haAdapter;
    private final Zigbee2MqttAdapter z2mAdapter;
    private final OllamaClient ollamaClient;
    private final ObjectProvider<BuildProperties> buildProperties;

    public PlatformInfoService(HomeAssistantAdapter haAdapter, Zigbee2MqttAdapter z2mAdapter,
                                OllamaClient ollamaClient, ObjectProvider<BuildProperties> buildProperties) {
        this.haAdapter = haAdapter;
        this.z2mAdapter = z2mAdapter;
        this.ollamaClient = ollamaClient;
        this.buildProperties = buildProperties;
    }

    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", versions());
        out.put("hardware", HARDWARE_CATALOG);
        out.put("aiDisclosure", aiDisclosure());
        return out;
    }

    private Map<String, Object> versions() {
        Map<String, Object> v = new LinkedHashMap<>();
        BuildProperties props = buildProperties.getIfAvailable();
        v.put("cabinBackend", props != null ? props.getVersion() : "unknown (build-info not present -- run via mvn package, not test-compile)");
        v.put("homeAssistant", haAdapter.fetchVersion("cabin").orElse("unavailable (HA unreachable or token not configured)"));
        v.put("zigbee2mqtt", z2mAdapter.getBridgeVersion().orElse("unavailable (no bridge/info received yet)"));
        v.put("ollama", ollamaClient.fetchVersion().orElse("unavailable (Ollama unreachable)"));
        v.put("mqttBroker", "not exposed by Mosquitto over MQTT -- no version topic to read");
        return v;
    }

    private Map<String, Object> aiDisclosure() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("model", "llama3.2:3b (Ollama)");
        d.put("hostedWhere", "Locally, on the cabin M920q -- not a cloud/vendor API");
        d.put("networkExposure", "Tailscale-only; no public port");
        d.put("dataHandling", "No prompt, question, or cabin data is ever sent to an external AI service. "
            + "If Ollama is unreachable, Tiny Helpdesk falls back to raw retrieved facts rather than failing silently or calling out.");
        return d;
    }

    private record HardwareRow(String category, String description) {}

    private static final List<HardwareRow> HARDWARE_CATALOG = List.of(
        new HardwareRow("Host", "Lenovo ThinkCentre M920q, Ubuntu 24.04, 32 GB RAM (cabin)"),
        new HardwareRow("Zigbee coordinator", "SONOFF ZBDongle-E (Sonoff Dongle Plus V2), ember adapter, USB on the M920q"),
        new HardwareRow("Cameras", "Blink cameras, bridged via blinkbridge into Frigate (detection/recording) and MediaMTX (RTSP relay)"),
        new HardwareRow("Environmental", "Zigbee temperature/humidity sensors: mechanical room, kitchen, outdoor low-temperature probe"),
        new HardwareRow("Refrigeration", "No refrigeration appliance is currently paired -- a future integration is tracked in docs/ontology/DECISIONS.md (D10)"),
        new HardwareRow("Sensors", "Kidde CO/temperature/humidity monitor (Home Assistant integration; electrochemical CO sensor + MOX temperature/humidity); Zigbee water leak (mechanical room, fridge, dishwasher, bathroom); door/motion contact (front door, second door, entry); main water valve actuator; breaker box smart switch")
    );
}
