package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.model.*;
import com.cabin.orchestrator.events.AlertSeverityClassifier;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges one or more Zigbee2MQTT instances into the DeviceRegistry.
 *
 * Every configured bridge shares the same MQTT broker connection
 * ({@code cabin.mqtt.brokerUrl}) but has its own topic prefix and location
 * (see {@code cabin.zigbee.topicPrefixes}/{@code cabin.zigbee.locations},
 * comma-separated and matched by index) — this is how Home's Termux
 * collector (its own Z2M instance, published to this same broker over
 * Tailscale) and Cabin's real 13-device mesh coexist without colliding on
 * retained topics like {@code bridge/state}/{@code bridge/devices}. Each
 * bridge has its own {@code knownFriendlyNames} scope so a friendly name
 * reused across two physical meshes can't cross-contaminate device state.
 *
 * Subscriptions (per bridge, {@code <prefix>} e.g. "zigbee2mqtt"):
 *   {@code <prefix>/bridge/devices}        — device list (device discovery)
 *   {@code <prefix>/bridge/state}          — bridge health
 *   {@code <prefix>/{friendly_name}}       — per-device state updates
 *
 * Publishes (to every configured bridge):
 *   {@code <prefix>/bridge/request/permit_join}   — open/close pairing window
 *   {@code <prefix>/{friendly_name}/set}          — commands to device
 *
 * Device IDs are prefixed with "z2m-" to avoid collision with MQTT/HA
 * devices. Cabin's bridge keeps the original unqualified "z2m-<friendlyName>"
 * scheme (every already-persisted cabin device id — DeviceLifecycleRecord
 * rows, ontology mappings, display configs — stays byte-identical); any
 * other location gets a "z2m-<location>-<friendlyName>" id instead. See
 * {@link #deviceId(ZigbeeBridge, String)}.
 */
@Service
public class Zigbee2MqttAdapter implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(Zigbee2MqttAdapter.class);
    private static final String DEVICE_ID_PREFIX = "z2m-";

    @Value("${cabin.mqtt.brokerUrl:tcp://localhost:1883}")
    private String brokerUrl;

    // Comma-separated, same order/count as locationsConfig -- e.g.
    // "zigbee2mqtt,home_z2m" / "cabin,home". Field initializers
    // (not just the @Value default) matter here: plain `new
    // Zigbee2MqttAdapter(...)` unit tests never go through Spring's
    // property injection, so without a literal default these would stay
    // null and buildBridges() would have nothing to parse.
    @Value("${cabin.zigbee.topicPrefixes:zigbee2mqtt}")
    private String topicPrefixesConfig = "zigbee2mqtt";

    @Value("${cabin.zigbee.locations:cabin}")
    private String locationsConfig = "cabin";

    private List<ZigbeeBridge> bridges;

    private MqttClient client;
    private final DeviceRegistry registry;
    private final EventPublisher eventPublisher;
    private final SignalQualityRegistry signalQualityRegistry;
    private final DeviceReportingRelationshipRepository reportingRelationshipRepository;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    // Bridge health is intentionally still a single, shared value across
    // every configured bridge (not per-bridge) -- DeviceHealthMonitor's
    // /api/system/health "zigbeeBridge" field and PlatformInfoService's
    // platform-info panel both consume it as one scalar today, and giving
    // Home its own bridge health surface is a separate, UI-facing change
    // this pass deliberately doesn't take on. Whichever bridge last
    // published wins; device-level state/location correctness (the actual
    // goal here) doesn't depend on this.
    private volatile String bridgeState = "offline";
    private volatile String bridgeVersion = null;

    /** One configured Zigbee2MQTT instance: its own topic namespace, location, and known-device set. */
    private static final class ZigbeeBridge {
        final String topicPrefix;
        final String location;
        final Set<String> knownFriendlyNames = ConcurrentHashMap.newKeySet();

        ZigbeeBridge(String topicPrefix, String location) {
            this.topicPrefix = topicPrefix;
            this.location = location;
        }

        String prefix() { return topicPrefix + "/"; }
    }

    @Autowired
    public Zigbee2MqttAdapter(DeviceRegistry registry, EventPublisher eventPublisher,
                               SignalQualityRegistry signalQualityRegistry,
                               DeviceReportingRelationshipRepository reportingRelationshipRepository) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.signalQualityRegistry = signalQualityRegistry;
        this.reportingRelationshipRepository = reportingRelationshipRepository;
    }

    /** Convenience constructor for isolated unit tests that don't care about D7 persistence. */
    public Zigbee2MqttAdapter(DeviceRegistry registry, EventPublisher eventPublisher,
                               SignalQualityRegistry signalQualityRegistry) {
        this(registry, eventPublisher, signalQualityRegistry, new DeviceReportingRelationshipRepository() {
            @Override public void upsert(DeviceReportingRelationship relationship) { }
            @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) { return List.of(); }
            @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.of(); }
        });
    }

    /**
     * Lazily built, then cached. Safe to call from either connect() or a
     * messageArrived() callback regardless of ordering: Spring resolves
     * every @Value field before @PostConstruct runs, and no MQTT message
     * can arrive before connect() has a client to receive it on, so by the
     * time this is first called the config fields already hold their real
     * values (Spring-injected in production, the literal field defaults in
     * a bare `new Zigbee2MqttAdapter(...)` unit test).
     */
    private List<ZigbeeBridge> bridges() {
        if (bridges == null) {
            bridges = buildBridges(topicPrefixesConfig, locationsConfig);
        }
        return bridges;
    }

    /** Package-private test hook: configures multiple bridges without a Spring context/property injection. */
    void configureBridgesForTest(String topicPrefixesCsv, String locationsCsv) {
        this.topicPrefixesConfig = topicPrefixesCsv;
        this.locationsConfig = locationsCsv;
        this.bridges = null;
    }

    private static List<ZigbeeBridge> buildBridges(String prefixesCsv, String locationsCsv) {
        List<String> prefixes = splitCsv(prefixesCsv);
        List<String> locations = splitCsv(locationsCsv);
        if (prefixes.isEmpty()) prefixes = List.of("zigbee2mqtt");
        List<ZigbeeBridge> result = new ArrayList<>();
        for (int i = 0; i < prefixes.size(); i++) {
            String location = i < locations.size() ? locations.get(i) : "cabin";
            result.add(new ZigbeeBridge(prefixes.get(i), location));
        }
        return result;
    }

    private static List<String> splitCsv(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    /** Which configured bridge a topic belongs to, or null if it matches none of them. */
    private ZigbeeBridge resolveBridge(String topic) {
        for (ZigbeeBridge bridge : bridges()) {
            if (topic.startsWith(bridge.prefix())) return bridge;
        }
        return null;
    }

    private static String deviceId(ZigbeeBridge bridge, String friendlyName) {
        String slug = friendlyName.replace(" ", "_");
        return "cabin".equals(bridge.location)
            ? DEVICE_ID_PREFIX + slug
            : DEVICE_ID_PREFIX + bridge.location + "-" + slug;
    }

    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(brokerUrl, "z2m-adapter-" + UUID.randomUUID());
            client.setCallback(this);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            client.connect(opts);
            for (ZigbeeBridge bridge : bridges()) {
                client.subscribe(bridge.prefix() + "bridge/devices", 1);
                client.subscribe(bridge.prefix() + "bridge/state", 1);
                client.subscribe(bridge.prefix() + "#", 0);
            }
            log.info("Zigbee2MQTT adapter connected to {} ({} bridge(s): {})",
                brokerUrl, bridges().size(), bridges().stream().map(b -> b.topicPrefix + "=" + b.location).toList());
        } catch (MqttException e) {
            log.warn("Zigbee2MQTT adapter connect failed (Z2M may not be running): {}", e.getMessage());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload());
            // isRetained() is true only when the broker is replaying its last-known
            // value because we just (re)subscribed -- e.g. after a cabin-backend
            // restart -- not when a device genuinely publishes something new while
            // we're already listening. Found 2026-09-01: a backend redeploy
            // resubscribed this adapter to zigbee2mqtt/#, Mosquitto replayed
            // main_water_valve's old retained availability/state, and this handler
            // stamped it with Instant.now() same as a live message -- making a
            // physically-dead valve (silent since 2026-08-21) briefly show a fresh
            // lastSeen and "back online" for exactly one health-check cycle before
            // going stale again. See handleAvailability/handleDeviceState below.
            boolean retained = message.isRetained();
            ZigbeeBridge bridge = resolveBridge(topic);
            if (bridge == null) return; // not one of our configured bridges
            String prefix = bridge.prefix();
            if (topic.equals(prefix + "bridge/devices")) {
                handleBridgeDeviceList(bridge, payload);
            } else if (topic.equals(prefix + "bridge/state")) {
                handleBridgeState(payload);
            } else if (topic.equals(prefix + "bridge/info")) {
                handleBridgeInfo(payload);
            } else if (!topic.contains("/set") && !topic.contains("/get")) {
                String friendlyName = topic.substring(prefix.length());
                if (friendlyName.endsWith("/availability")) {
                    // Z2M availability is the authoritative online/offline signal — use it directly
                    String name = friendlyName.substring(0, friendlyName.lastIndexOf("/availability"));
                    if (bridge.knownFriendlyNames.contains(name)) handleAvailability(bridge, name, payload, retained);
                } else if (!friendlyName.startsWith("bridge/") && bridge.knownFriendlyNames.contains(friendlyName)) {
                    handleDeviceState(bridge, friendlyName, payload, retained);
                }
            }
        } catch (Exception e) {
            log.warn("Z2M message processing error on {}: {}", topic, e.getMessage());
        }
    }

    private void handleBridgeState(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            bridgeState = node.has("state") ? node.get("state").asText() : payload.trim();
            log.debug("Z2M bridge state: {}", bridgeState);
        } catch (Exception e) {
            bridgeState = payload.trim();
        }
    }

    /** zigbee2mqtt/bridge/info -- retained, published on startup/config change. Version only, for the platform-info panel. */
    private void handleBridgeInfo(String payload) {
        try {
            JsonNode node = mapper.readTree(payload);
            if (node.has("version")) {
                bridgeVersion = node.get("version").asText();
                log.debug("Z2M bridge version: {}", bridgeVersion);
            }
        } catch (Exception e) {
            log.warn("Failed to parse Z2M bridge/info: {}", e.getMessage());
        }
    }

    private void handleAvailability(ZigbeeBridge bridge, String friendlyName, String payload, boolean retained) {
        String deviceId = deviceId(bridge, friendlyName);
        try {
            JsonNode node = mapper.readTree(payload);
            String avail = node.has("state") ? node.get("state").asText() : payload.trim();
            DeviceStatus existing = registry.get(deviceId);
            if (existing == null) return;
            String newState = "online".equalsIgnoreCase(avail) ? "ONLINE" : "OFFLINE";
            // A retained replay only proves what the broker last cached, not that
            // the device said anything just now -- keep the prior lastSeen so a
            // reconnect/resubscribe can't reset the staleness clock on its own.
            Instant lastSeen = retained ? existing.lastSeen() : Instant.now();
            // Only update state, preserve existing attributes
            registry.update(new DeviceStatus(
                deviceId, existing.type(), existing.name(), newState,
                lastSeen, existing.attributes(), existing.location()));
            log.debug("Z2M availability: {} -> {}{}", friendlyName, newState, retained ? " (retained replay)" : "");
        } catch (Exception e) {
            log.warn("Failed to parse Z2M availability for {}: {}", friendlyName, e.getMessage());
        }
    }

    /**
     * Parses the zigbee2mqtt/bridge/devices array and refreshes every device.
     * Each element has: ieee_address, friendly_name, type, definition.exposes[]
     */
    private void handleBridgeDeviceList(ZigbeeBridge bridge, String payload) {
        try {
            JsonNode devices = mapper.readTree(payload);
            if (!devices.isArray()) return;
            for (JsonNode device : devices) {
                String friendlyName = device.path("friendly_name").asText(null);
                if (friendlyName == null || friendlyName.equals("Coordinator")) continue;
                bridge.knownFriendlyNames.add(friendlyName);
                String deviceId = deviceId(bridge, friendlyName);

                JsonNode definition = device.path("definition");
                Set<DeviceCapability> caps = inferCapabilities(definition);
                DeviceType type = inferType(definition, caps);
                List<String> vendorReportedFields = extractVendorReportedFields(definition, type);

                DeviceDescriptor desc = new DeviceDescriptor(
                    deviceId,
                    friendlyName,
                    type,
                    caps,
                    "mqtt",
                    bridge.prefix() + friendlyName,
                    false,
                    bridge.location
                );
                Map<String, Object> discovery = new LinkedHashMap<>();
                discovery.put("discoveredFrom", "Zigbee2MQTT bridge/devices");
                discovery.put("model", definition.path("model").asText(""));
                discovery.put("vendor", definition.path("vendor").asText(""));
                // D7 (docs/ontology/DECISIONS.md): forwarded wholesale to
                // cabin-discovery via DiscoveryServiceClient's discoveryAttributes
                // passthrough -- its LocalCatalogProvider uses this for a free,
                // vendor-confirmed match with zero network call, before ever
                // trying a paid/AI lookup. Omitted entirely (not an empty list)
                // when there's nothing real to report, so the Python side's
                // simple truthiness check can't mistake "we checked and found
                // nothing" for "we never checked."
                if (!vendorReportedFields.isEmpty()) {
                    discovery.put("vendorReportedFields", vendorReportedFields);
                }
                String powerSource = device.path("power_source").asText(
                    definition.path("power_source").asText("unknown"));
                discovery.put("powerSource", powerSource);
                if ("battery".equalsIgnoreCase(powerSource)) {
                    discovery.put("expectedCheckinMinutes", 1560); // 26h: accommodates daily sleepy-device reports
                } else {
                    // A nullable discovery value is an explicit removal in the
                    // registry. Otherwise a device reclassified as mains-powered
                    // would retain its stale 26-hour battery grace window.
                    discovery.put("expectedCheckinMinutes", null);
                }
                boolean firstSeen = registry.registerCandidate(desc, discovery);
                if (firstSeen) log.info("Z2M discovered new device: {} ({})", friendlyName, type);
                else log.debug("Z2M refreshed device: {} ({})", friendlyName, type);
                // D7 persistence (issue #31): vendor_spec is the highest-trust
                // ConfirmationSource short of a manual override, so this never
                // needs to check what's already there -- upsert()'s own
                // priority rule handles that. measurement_type == semanticField
                // here because D7MeasurementTypes is already the exact
                // vocabulary this schema's measurement_type enum uses.
                Instant confirmedNow = Instant.now();
                for (String field : vendorReportedFields) {
                    reportingRelationshipRepository.upsert(new DeviceReportingRelationship(
                        deviceId, field, field, ConfirmationSource.VENDOR_SPEC, confirmedNow));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Z2M device list: {}", e.getMessage());
        }
    }

    /**
     * Handles per-device state messages. The Z2M payload is a flat JSON object
     * with property names matching the 'property' fields from definition.exposes.
     * Unknown properties are stored in attributes as-is.
     */
    private void handleDeviceState(ZigbeeBridge bridge, String friendlyName, String payload, boolean retained) {
        String deviceId = deviceId(bridge, friendlyName);
        try {
            JsonNode node = mapper.readTree(payload);
            if (!node.isObject()) return;

            DeviceStatus existing = registry.get(deviceId);
            if (existing == null) return; // not registered yet; bridge/devices will handle it

            Map<String, Object> attrs = new LinkedHashMap<>(existing.attributes());
            node.fields().forEachRemaining(e -> attrs.put(e.getKey(), jsonNodeToValue(e.getValue())));

            // PROTOTYPE, 2026-08-08 -- see SignalQualityRegistry's own
            // comment. linkquality was already parsed into attrs on every
            // message (deriveState below already reads it); this just
            // also trends it so a future evaluation pass has real history
            // to look at, not just the always-overwritten latest value.
            if (attrs.get("linkquality") instanceof Number lqi) {
                signalQualityRegistry.record(deviceId, lqi.intValue());
            }

            String state = deriveState(attrs, existing.type());
            // See messageArrived's own comment: a retained replay is Mosquitto
            // handing back its last cached value on (re)subscribe, not a fresh
            // report -- preserve the prior lastSeen instead of resetting the
            // staleness clock, and skip the cabin_event write below since
            // nothing actually happened just now.
            Instant lastSeen = retained ? existing.lastSeen() : Instant.now();
            registry.update(new DeviceStatus(
                deviceId, existing.type(), existing.name(), state,
                lastSeen, attrs, existing.location()));
            if (retained) return;

            // Same publish MqttBridgeService.handleDeviceMessage() does for
            // non-Zigbee devices — this adapter was updating DeviceRegistry
            // (live state) without ever writing to cabin_event, so Zigbee
            // motion/contact/etc. activity never showed up in event history.
            CabinEvent event = new CabinEvent(
                UUID.randomUUID().toString(), deviceId, "TELEMETRY",
                AlertSeverityClassifier.classify(attrs), Instant.now(), attrs);
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("Failed to parse Z2M state for {}: {}", friendlyName, e.getMessage());
        }
    }

    /**
     * Infers DeviceCapabilities from definition.exposes[].
     * Handles generic feature types ('binary', 'numeric', 'enum') and
     * composite features that have sub-features (e.g. water_leak_buzzer
     * nested under a composite).
     */
    private Set<DeviceCapability> inferCapabilities(JsonNode definition) {
        Set<DeviceCapability> caps = new HashSet<>();
        if (definition.isMissingNode()) {
            caps.add(DeviceCapability.TELEMETRY);
            return caps;
        }
        JsonNode exposes = definition.path("exposes");
        if (!exposes.isArray()) {
            caps.add(DeviceCapability.TELEMETRY);
            return caps;
        }
        for (JsonNode expose : exposes) {
            processExpose(expose, caps);
        }
        if (caps.isEmpty()) caps.add(DeviceCapability.TELEMETRY);
        return caps;
    }

    private void processExpose(JsonNode expose, Set<DeviceCapability> caps) {
        String type = expose.path("type").asText("");
        String property = expose.path("property").asText("");
        String name = expose.path("name").asText("");
        int access = expose.path("access").asInt(1);
        boolean writable = (access & 2) != 0;

        switch (type) {
            case "binary" -> {
                if (property.contains("water_leak") || property.contains("smoke") ||
                    property.contains("alarm") || property.contains("tamper")) {
                    caps.add(DeviceCapability.ALARM);
                } else if (property.contains("occupancy") || property.contains("presence")) {
                    caps.add(DeviceCapability.PRESENCE);
                } else if (property.contains("contact") || property.contains("lock")) {
                    if (writable) caps.add(DeviceCapability.COMMAND);
                    caps.add(DeviceCapability.ACCESS_CONTROL);
                } else {
                    caps.add(DeviceCapability.TELEMETRY);
                    if (writable) caps.add(DeviceCapability.COMMAND);
                }
            }
            case "numeric" -> {
                caps.add(DeviceCapability.TELEMETRY);
                if (writable) caps.add(DeviceCapability.COMMAND);
                if (property.contains("power") || property.contains("energy") ||
                    property.contains("current") || property.contains("voltage")) {
                    caps.add(DeviceCapability.POWER_MONITOR);
                }
            }
            case "enum" -> {
                caps.add(DeviceCapability.TELEMETRY);
                if (writable) caps.add(DeviceCapability.COMMAND);
            }
            case "climate" -> {
                caps.add(DeviceCapability.TELEMETRY);
                caps.add(DeviceCapability.COMMAND);
                caps.add(DeviceCapability.CLIMATE);
            }
            case "lock" -> {
                caps.add(DeviceCapability.COMMAND);
                caps.add(DeviceCapability.ACCESS_CONTROL);
            }
            case "composite" -> {
                // Recurse into sub-features (e.g. water_leak_buzzer on THIRDREALITY)
                JsonNode features = expose.path("features");
                if (features.isArray()) {
                    for (JsonNode sub : features) processExpose(sub, caps);
                }
            }
            default -> {
                caps.add(DeviceCapability.TELEMETRY);
                if (writable) caps.add(DeviceCapability.COMMAND);
            }
        }
    }

    // D7 (docs/ontology/DECISIONS.md), Option B: the real reporting-
    // relationship names docs/ontology/schema/device-reporting-relationship.schema.json
    // recognizes -- deliberately closed, matching that schema's own enum,
    // not every numeric key Z2M happens to expose. Confirmed live against
    // z2m-temp_kitchen's real exposes[] (2026-08-29) that a naive "every
    // numeric expose" mapping would have wrongly included temperature_calibration/
    // humidity_calibration (category: "config", i.e. a writable setting, not
    // a reported measurement) and linkquality (a real diagnostic value, not
    // an environmental/utility measurement type this schema models) --
    // filtering on both category and the closed D7MeasurementTypes vocabulary
    // is why the exclusion holds even for a device whose exposes happen to
    // use the same "numeric" type for a setting as for a real reading.
    // (voltage/current used to be excluded here too, on the same
    // "diagnostic, not measurement" reasoning -- D15, 2026-09-05, ratifies
    // them as real measurement types specifically for power-monitoring
    // devices, so they're now in D7MeasurementTypes like everything else.)
    //
    // Fixed 2026-09-05: this used to be a second, independent Set literal
    // instead of calling D7MeasurementTypes -- despite that class's own doc
    // comment already claiming this adapter used it, it never actually did,
    // so the two vocabularies could silently drift. Found while adding D15's
    // three new values; now there is exactly one place to extend.

    // D16 (Reporting Topics IA), Cowork ratification 2026-09-05: motion/
    // contact/leak service entities never existed at all before this --
    // confirmed live no device_reporting_relationship row existed for any
    // of z2m-motion_entry/z2m-door_front_contact/z2m-leak_mech_room's actual
    // binary reading (only their incidental numeric battery/voltage rows
    // did), because this method only ever captured "numeric" exposes.
    // DeviceType-keyed, not bare-field-name-keyed, per Cowork's own
    // refinement (same reasoning as the voltage/current device-context fix
    // right below in the vocabulary comment): a field name alone isn't a
    // safe signal on its own, so this only recognizes a device's binary
    // presence reading when the device is actually classified as the
    // matching DeviceType -- not "every binary expose named occupancy/
    // contact/water_leak regardless of what kind of device sent it."
    private static final Map<DeviceType, String> PRESENCE_FIELD_BY_DEVICE_TYPE = Map.of(
        DeviceType.MOTION_SENSOR, "occupancy",
        DeviceType.CONTACT_SENSOR, "contact",
        DeviceType.WATER_LEAK_SENSOR, "water_leak"
    );

    private List<String> extractVendorReportedFields(JsonNode definition, DeviceType deviceType) {
        List<String> fields = new ArrayList<>();
        JsonNode exposes = definition.path("exposes");
        if (exposes.isArray()) {
            for (JsonNode expose : exposes) collectVendorReportedFields(expose, fields, deviceType);
        }
        return fields;
    }

    private void collectVendorReportedFields(JsonNode expose, List<String> fields, DeviceType deviceType) {
        String type = expose.path("type").asText("");
        if ("composite".equals(type)) {
            JsonNode features = expose.path("features");
            if (features.isArray()) {
                for (JsonNode sub : features) collectVendorReportedFields(sub, fields, deviceType);
            }
            return;
        }
        String category = expose.path("category").asText("");
        String name = expose.path("name").asText("");
        boolean recognizedNumeric = "numeric".equals(type) && !"config".equals(category);
        boolean recognizedPresenceSignal = "binary".equals(type) && name.equals(PRESENCE_FIELD_BY_DEVICE_TYPE.get(deviceType));
        if ((recognizedNumeric || recognizedPresenceSignal) && D7MeasurementTypes.toMeasurementType(name).isPresent()) {
            fields.add(name);
        }
    }

    private DeviceType inferType(JsonNode definition, Set<DeviceCapability> caps) {
        String model = definition.path("model").asText("").toLowerCase();
        String desc = definition.path("description").asText("").toLowerCase();
        String vendor = definition.path("vendor").asText("").toLowerCase();

        if (model.contains("snzb-05") || desc.contains("water leak")) return DeviceType.WATER_LEAK_SENSOR;
        if (model.contains("snzb-03") || desc.contains("motion"))      return DeviceType.MOTION_SENSOR;
        if (model.contains("snzb-04") || desc.contains("contact"))     return DeviceType.CONTACT_SENSOR;
        if (model.contains("snzb-02") || desc.contains("temperature")) return DeviceType.TEMPERATURE_SENSOR;
        if (model.contains("zbminir") || desc.contains("switch"))      return DeviceType.HOME_ASSISTANT_ENTITY;
        if (desc.contains("smoke") || desc.contains("co alarm"))       return DeviceType.SMOKE_ALARM;
        if (desc.contains("smart plug") || desc.contains("outlet"))    return DeviceType.POWER_METER;
        if (caps.contains(DeviceCapability.CLIMATE))                   return DeviceType.THERMOSTAT;
        if (caps.contains(DeviceCapability.ACCESS_CONTROL))            return DeviceType.LOCK;
        if (caps.contains(DeviceCapability.ALARM))                     return DeviceType.WATER_LEAK_SENSOR;
        return DeviceType.HOME_ASSISTANT_ENTITY;
    }

    private String deriveState(Map<String, Object> attrs, DeviceType type) {
        // Water leak / alarm sensors
        Object waterLeak = attrs.get("water_leak");
        if (Boolean.TRUE.equals(waterLeak)) return "ALARM";
        Object smoke = attrs.get("smoke");
        if (Boolean.TRUE.equals(smoke)) return "ALARM";
        // Generic alarm property
        Object alarm = attrs.get("alarm");
        if (Boolean.TRUE.equals(alarm)) return "ALARM";
        // Contact/motion: report as ONLINE but leave state informational in attrs
        Object linkquality = attrs.get("linkquality");
        if (linkquality == null) return "UNKNOWN";
        return "ONLINE";
    }

    private Object jsonNodeToValue(JsonNode node) {
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber())  return node.numberValue();
        if (node.isTextual()) return node.textValue();
        return node.toString();
    }

    /**
     * Open or close the Zigbee pairing window on every configured bridge.
     * duration=254 = max (4m14s). Broadcasting to every bridge rather than
     * targeting one is deliberate and safe: a Zigbee join is RF-proximity
     * bound, so opening pairing on a coordinator the new device physically
     * can't reach is a no-op there, not a wrong-mesh pairing risk. This
     * keeps the existing single-argument /api/devices/permit-join endpoint
     * working unchanged even once a second (Home) bridge is configured.
     */
    public void permitJoin(boolean enable, int duration) {
        if (client == null || !client.isConnected()) {
            log.warn("Z2M not connected — cannot permit_join");
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("value", enable);
            if (enable) body.put("time", duration);
            String json = mapper.writeValueAsString(body);
            for (ZigbeeBridge bridge : bridges()) {
                client.publish(bridge.prefix() + "bridge/request/permit_join",
                    new MqttMessage(json.getBytes()));
            }
            log.info("Z2M permit_join={} duration={}s ({} bridge(s))", enable, duration, bridges().size());
        } catch (Exception e) {
            log.error("Z2M permit_join failed: {}", e.getMessage());
        }
    }

    /** Send a property update to a Zigbee device (e.g. water_leak_buzzer: true). */
    public boolean sendCommand(String friendlyName, Map<String, Object> payload) {
        if (client == null || !client.isConnected()) return false;
        try {
            String json = mapper.writeValueAsString(payload);
            client.publish(bridgeForFriendlyName(friendlyName).prefix() + friendlyName + "/set",
                new MqttMessage(json.getBytes()));
            return true;
        } catch (Exception e) {
            log.error("Z2M sendCommand to {} failed: {}", friendlyName, e.getMessage());
            return false;
        }
    }

    /** Falls back to the first/default bridge for a name none of them have seen yet -- matches pre-multi-bridge behavior. */
    private ZigbeeBridge bridgeForFriendlyName(String friendlyName) {
        for (ZigbeeBridge bridge : bridges()) {
            if (bridge.knownFriendlyNames.contains(friendlyName)) return bridge;
        }
        return bridges().get(0);
    }

    public String getBridgeState() { return bridgeState; }
    public Optional<String> getBridgeVersion() { return Optional.ofNullable(bridgeVersion); }

    public Set<String> getKnownFriendlyNames() {
        Set<String> all = new LinkedHashSet<>();
        for (ZigbeeBridge bridge : bridges()) all.addAll(bridge.knownFriendlyNames);
        return Collections.unmodifiableSet(all);
    }

    @Override public void connectionLost(Throwable cause) {
        log.warn("Z2M adapter connection lost: {}", cause.getMessage());
    }

    @Override public void deliveryComplete(IMqttDeliveryToken token) {}

    @PreDestroy
    public void disconnect() {
        try { if (client != null && client.isConnected()) client.disconnect(); }
        catch (MqttException ignored) {}
    }
}
