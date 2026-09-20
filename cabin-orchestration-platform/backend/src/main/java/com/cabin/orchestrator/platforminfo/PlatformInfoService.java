package com.cabin.orchestrator.platforminfo;

import com.cabin.orchestrator.helpdesk.OllamaClient;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 2026-09-20: also the full platform specs -- every versioned thing the platform is
 * built from or runs on (platform-specs.yaml, kept in step with the repo by
 * PlatformSpecsGuardTest), each with how firmly it is pinned and, where the backend
 * can ask, the version that is actually running.
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
    private final PlatformSpecsCatalog catalog;
    private final JdbcTemplate jdbc;

    @Autowired
    public PlatformInfoService(HomeAssistantAdapter haAdapter, Zigbee2MqttAdapter z2mAdapter,
                                OllamaClient ollamaClient, ObjectProvider<BuildProperties> buildProperties,
                                PlatformSpecsCatalog catalog, JdbcTemplate jdbc) {
        this.haAdapter = haAdapter;
        this.z2mAdapter = z2mAdapter;
        this.ollamaClient = ollamaClient;
        this.buildProperties = buildProperties;
        this.catalog = catalog;
        this.jdbc = jdbc;
    }

    /** Isolated unit tests: the real catalog, no database. */
    public PlatformInfoService(HomeAssistantAdapter haAdapter, Zigbee2MqttAdapter z2mAdapter,
                                OllamaClient ollamaClient, ObjectProvider<BuildProperties> buildProperties) {
        this(haAdapter, z2mAdapter, ollamaClient, buildProperties, new PlatformSpecsCatalog(), null);
    }

    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("versions", versions());
        out.put("specs", specs());
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

    /**
     * The catalog, group by group, with the running version filled in for the entries
     * the backend can probe (null when it can't, or when the probe finds nothing) and a
     * count of how each entry is pinned so what may need maintenance stands out.
     */
    private Map<String, Object> specs() {
        Map<String, Optional<String>> live = new LinkedHashMap<>();
        for (String key : PlatformSpecsCatalog.LIVE_KEYS) live.put(key, probe(key));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String t : List.of("pinned", "series", "floating", "unmanaged")) counts.put(t, 0);

        List<Map<String, Object>> groups = new ArrayList<>();
        for (PlatformSpecsCatalog.Group g : catalog.groups()) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (PlatformSpecsCatalog.Item i : g.items()) {
                counts.merge(i.track(), 1, Integer::sum);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", i.id());
                m.put("name", i.name());
                m.put("declared", i.declared());
                m.put("track", i.track());
                m.put("running", i.live() == null ? null : live.getOrDefault(i.live(), Optional.empty()).orElse(null));
                m.put("liveProbe", i.live() != null);
                m.put("locked", i.locked());
                m.put("sources", i.sources());
                m.put("note", i.note());
                items.add(m);
            }
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("id", g.id());
            group.put("label", g.label());
            group.put("items", items);
            groups.add(group);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", catalog.items().size());
        out.put("counts", counts);
        out.put("groups", groups);
        return out;
    }

    /** One running-version probe. Never throws: an unreachable service is simply "not known". */
    private Optional<String> probe(String key) {
        try {
            return switch (key) {
                case "java" -> Optional.of(Runtime.version().toString());
                case "spring-boot" -> Optional.ofNullable(SpringBootVersion.getVersion());
                case "cabin-backend" -> Optional.ofNullable(buildProperties.getIfAvailable()).map(BuildProperties::getVersion);
                case "home-assistant" -> haAdapter.fetchVersion("cabin");
                case "zigbee2mqtt" -> z2mAdapter.getBridgeVersion();
                case "ollama" -> ollamaClient.fetchVersion();
                case "postgres" -> postgresVersion();
                default -> Optional.empty();
            };
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<String> postgresVersion() {
        if (jdbc == null) return Optional.empty();
        String server = jdbc.queryForObject("SHOW server_version", String.class);
        List<String> timescale = jdbc.queryForList("SELECT extversion FROM pg_extension WHERE extname = 'timescaledb'", String.class);
        if (server == null) return Optional.empty();
        String base = "PostgreSQL " + server.split(" ")[0];
        return Optional.of(timescale.isEmpty() ? base : base + " with TimescaleDB " + timescale.get(0));
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
        new HardwareRow("Refrigeration", "Liebherr smart fridge/freezer (\"Loonie Mc Frigerton\", Home Assistant integration) -- top/bottom zone temperature + humidity, NightMode, SuperCool, SuperFrost, PartyMode, IceMaker state. Corrected 2026-09-04: this row previously said none was paired, which was wrong -- direct user correction, verified live against /api/devices."),
        new HardwareRow("Sensors", "Kidde CO/temperature/humidity monitor (Home Assistant integration; electrochemical CO sensor + MOX temperature/humidity); Zigbee water leak (mechanical room, fridge, dishwasher, bathroom); door/motion contact (front door, second door, entry); main water valve actuator; breaker box smart switch")
    );
}
