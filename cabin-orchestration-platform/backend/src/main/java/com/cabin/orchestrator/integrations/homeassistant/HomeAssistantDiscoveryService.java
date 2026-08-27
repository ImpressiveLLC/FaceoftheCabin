package com.cabin.orchestrator.integrations.homeassistant;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.*;
import com.cabin.orchestrator.events.AlertSeverityClassifier;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns recognized Home Assistant entities into visible candidates and consumes
 * HA person/device-tracker/occupancy entities as presence signals. Discovery is
 * intentionally non-destructive: candidates cannot receive commands until a
 * user enables them in Device Manager.
 */
@Service
public class HomeAssistantDiscoveryService {
    // number/select added 2026-08-18: found live that the Liebherr fridge's
    // two setpoint entities (number.*) and its icemaker mode (select.*)
    // were silently skipped by this allowlist every discovery cycle,
    // forever -- no error, no log line, they just never became candidates.
    // 6 of the fridge's 9 real entities were already discoverable
    // (sensor/switch); this closes the remaining gap rather than leaving
    // a third of one real device permanently invisible regardless of what
    // a user does in Device Manager.
    private static final Set<String> DEVICE_DOMAINS = Set.of(
        "binary_sensor", "sensor", "lock", "climate", "switch", "light", "cover", "camera",
        "number", "select");

    private final HomeAssistantAdapter adapter;
    private final DeviceRegistry registry;
    private final PresenceSignalRegistry presenceSignals;
    private final PresenceService presenceService;
    private final EventPublisher eventPublisher;

    // deviceId -> the raw, as-discovered attrs (+ normalized state) from the
    // PREVIOUS poll cycle -- deliberately NOT compared against
    // DeviceRegistry.get()'s own return value. DeviceRegistry.withOntologyMetadata()
    // layers freshly-recomputed capabilities/category onto every .get() call,
    // and this class's own `merged` (built from that decorated view) then
    // gets written straight back via registry.update() -- comparing against
    // that decorated/round-tripped shape produced a false "changed" on the
    // very first poll of every entity (found writing this class's own test).
    // Tracking a private, undecorated snapshot sidesteps DeviceRegistry's
    // internal bookkeeping entirely.
    private final Map<String, Map<String, Object>> lastPolledAttrs = new ConcurrentHashMap<>();
    private final Map<String, String> lastPolledState = new ConcurrentHashMap<>();

    public HomeAssistantDiscoveryService(HomeAssistantAdapter adapter, DeviceRegistry registry,
            PresenceSignalRegistry presenceSignals, PresenceService presenceService, EventPublisher eventPublisher) {
        this.adapter = adapter;
        this.registry = registry;
        this.presenceSignals = presenceSignals;
        this.presenceService = presenceService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${cabin.homeassistant.discovery-interval-ms:60000}")
    public void discover() {
        discoverLocation("cabin");
        discoverLocation("home");
        presenceService.recomputeFromSignals();
    }

    void discoverLocation(String location) {
        // One bulk lookup per cycle, not one per entity -- see
        // HomeAssistantAdapter.deviceIdsByEntity's own javadoc for what
        // this is and why it fails safe to an empty map (blank token,
        // unreachable HA, template error). Composite grouping is then
        // just "these entities share the same haDeviceId attribute" --
        // no device_id resolved for an entity (helpers/templates
        // genuinely have none, or the lookup failed entirely) simply
        // means it stays ungrouped, exactly like every entity did before
        // this existed. Real device/UI grouping into a single expandable
        // card lives client-side (Device Manager) -- this only supplies
        // the raw fact.
        Map<String, String> deviceIds = adapter.deviceIdsByEntity(location);
        for (HomeAssistantAdapter.DiscoveredEntity entity : adapter.discover(location)) {
            String domain = domain(entity.entityId());
            if (isPresence(entity, domain)) {
                presenceSignals.record(location, entity.entityId(), isPresent(entity.state()));
            }
            if (!DEVICE_DOMAINS.contains(domain)) continue;

            String generatedId = "ha-" + location + "-" + entity.entityId().replace('.', '-').replace('_', '-');
            String id = registry.descriptorByConnection("ha_rest", entity.entityId(), location)
                .map(DeviceDescriptor::deviceId).orElse(generatedId);
            DeviceType type = inferType(domain, entity.attributes());
            Set<DeviceCapability> capabilities = inferCapabilities(domain, type);
            String name = String.valueOf(entity.attributes().getOrDefault("friendly_name", entity.entityId()));
            DeviceDescriptor descriptor = new DeviceDescriptor(id, name, type, capabilities,
                "ha_rest", entity.entityId(), false, location);
            Map<String, Object> attrs = new LinkedHashMap<>(entity.attributes());
            attrs.put("entityId", entity.entityId());
            attrs.put("discoveredFrom", "Home Assistant");
            // 2026-08-25: a sensor-domain entity's real reading IS HA's own
            // state field (e.g. "72.5" for a temperature sensor, "415" for
            // a CO2 sensor) -- HA's own convention, not a nested attribute
            // the way Zigbee's temperature/humidity/etc. are. Confirmed
            // live this was never captured anywhere: Kidde's indoor-
            // temperature/co2-level/humidity/etc. entities only ever
            // published metadata (device_class, unit_of_measurement,
            // friendly_name), never the actual number. mapHaState() below
            // stays a categorical device-HEALTH classifier (ONLINE/
            // UNKNOWN/ALARM) for DeviceStatus.state -- a different concern
            // from the reading itself, untouched here.
            if ("sensor".equals(domain)) {
                attrs.put("value", entity.state());
                // See semanticFieldFor()'s own doc: makes this reading
                // chartable/trigger-able exactly like a native Zigbee
                // device's payload, with no downstream special-casing.
                // Silently skipped (not stored as a string/NaN) when the
                // state genuinely isn't numeric yet -- e.g. HA reports
                // "unavailable" during a brief cloud-poll gap -- so a
                // stray non-numeric value never lands in a numeric column.
                String semanticField = semanticFieldFor(type);
                if (semanticField != null) {
                    try {
                        attrs.put(semanticField, Double.parseDouble(entity.state()));
                    } catch (NumberFormatException ignored) {
                        // not a number right now -- next poll cycle retries
                    }
                }
            }
            String haDeviceId = deviceIds.get(entity.entityId());
            if (haDeviceId != null && !haDeviceId.isBlank()) {
                attrs.put("haDeviceId", haDeviceId);
            }
            if (attrs.containsKey("battery_level") && !attrs.containsKey("battery")) {
                attrs.put("battery", attrs.get("battery_level"));
            }
            registry.registerCandidate(descriptor, attrs);
            DeviceStatus current = registry.get(id);
            if (current == null) continue; // device was removed concurrently
            DeviceDescriptor registered = registry.descriptor(id).orElse(descriptor);
            Map<String, Object> merged = new LinkedHashMap<>(current.attributes());
            merged.putAll(attrs);
            boolean safetyAlarm = Set.of(DeviceType.SMOKE_ALARM, DeviceType.CO_ALARM,
                DeviceType.WATER_LEAK_SENSOR).contains(registered.type()) && isPresent(entity.state());
            String newState = safetyAlarm ? "ALARM" : adapter.normalizedState(entity.state());
            registry.update(new DeviceStatus(id, registered.type(), registered.name(),
                newState, Instant.now(), merged, registered.location()));
            publishIfChanged(id, attrs, newState);
        }
    }

    /**
     * E5, 2026-08-21 -- the poll-to-event bridge. Before this, every HA
     * entity's live DeviceRegistry state refreshed every cycle
     * unconditionally, but nothing downstream (WorkflowRuleService
     * included) ever saw a *change* -- HomeAssistantAdapter has no
     * CabinEvent/eventPublisher reference anywhere. Diffs the raw,
     * as-discovered `attrs` against lastPolledAttrs (see that field's own
     * comment for why NOT DeviceRegistry.get()'s decorated view) and
     * publishes exactly one TELEMETRY event when the normalized state or
     * any attribute actually changed since the previous cycle. `haState`
     * is added to the payload alongside the raw attrs specifically because
     * an HA binary_sensor's meaningful value often *is* DeviceStatus.state
     * itself (on/off), not a nested attribute the way Zigbee's
     * water_leak/contact/etc. are -- trigger_ha_entity_state_changed
     * (docs/ontology.yaml) matches on it.
     *
     * delivery_mode: pull -- see that trigger's own ontology entry for the
     * full honesty note: this can only ever see what HA itself already
     * polls, on this method's own ~60s cycle, and is not a true push
     * channel. Also not verified against live Kidde/Liebherr attribute
     * payloads in this session -- if a given integration's `attributes`
     * happens to include something that legitimately changes every poll
     * (a raw timestamp, for instance), this would fire more often than a
     * real "something changed" signal should; a real running instance
     * should confirm this isn't spammy for a specific entity before
     * building anything rate-sensitive against it.
     */
    private void publishIfChanged(String deviceId, Map<String, Object> attrs, String newState) {
        Map<String, Object> previousAttrs = lastPolledAttrs.put(deviceId, attrs);
        String previousState = lastPolledState.put(deviceId, newState);
        if (previousAttrs == null) return; // first sight -- nothing to diff against yet
        if (Objects.equals(previousAttrs, attrs) && Objects.equals(previousState, newState)) return;
        Map<String, Object> payload = new LinkedHashMap<>(attrs);
        payload.put("haState", newState);
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), deviceId, "TELEMETRY",
            AlertSeverityClassifier.classify(payload), Instant.now(), payload));
    }

    private String domain(String entityId) {
        int dot = entityId.indexOf('.');
        return dot > 0 ? entityId.substring(0, dot) : "";
    }

    private boolean isPresence(HomeAssistantAdapter.DiscoveredEntity entity, String domain) {
        String deviceClass = String.valueOf(entity.attributes().getOrDefault("device_class", ""));
        return "person".equals(domain) || "device_tracker".equals(domain)
            || "presence".equals(deviceClass) || "occupancy".equals(deviceClass);
    }

    private boolean isPresent(String state) {
        return Set.of("home", "on", "present", "occupied").contains(state.toLowerCase(Locale.ROOT));
    }

    private DeviceType inferType(String domain, Map<String, Object> attrs) {
        String deviceClass = String.valueOf(attrs.getOrDefault("device_class", "")).toLowerCase(Locale.ROOT);
        if ("lock".equals(domain)) return DeviceType.LOCK;
        if ("climate".equals(domain)) return DeviceType.THERMOSTAT;
        if ("camera".equals(domain)) return DeviceType.CAMERA;
        if (deviceClass.contains("smoke")) return DeviceType.SMOKE_ALARM;
        // "carbon_monoxide"/"co" here is the BINARY alarm device_class (HA's
        // own convention for a binary_sensor's true/false trip state) -- the
        // numeric CO ppm *level* is a separate sensor entity, handled by the
        // unit_of_measurement fallback below since (found live, 2026-08-21,
        // see docs/MAINTENANCE.md) Kidde's own integration doesn't set any
        // device_class at all on that specific entity -- a vendor gap, not
        // something to assume is universal, but common enough with
        // HACS-distributed integrations to guard for generically.
        if (deviceClass.contains("carbon_monoxide") || deviceClass.equals("co")) return DeviceType.CO_ALARM;
        if (deviceClass.contains("moisture")) return DeviceType.WATER_LEAK_SENSOR;
        if (deviceClass.contains("motion") || deviceClass.contains("occupancy")) return DeviceType.MOTION_SENSOR;
        if (deviceClass.contains("door") || deviceClass.contains("opening")) return DeviceType.CONTACT_SENSOR;
        if (deviceClass.contains("temperature")) return DeviceType.TEMPERATURE_SENSOR;
        if (deviceClass.contains("humidity")) return DeviceType.HUMIDITY_SENSOR;
        if (deviceClass.contains("power") || deviceClass.contains("energy")) return DeviceType.POWER_METER;
        // Added 2026-08-27 for Kidde's HA-discovered air-quality entities
        // (indoor CO2/TVOC/air-quality-index) -- see
        // docs/ontology.yaml's smart_appliance_co_air_quality_kidde.
        if (deviceClass.contains("carbon_dioxide")) return DeviceType.CO2_SENSOR;
        if (deviceClass.contains("volatile_organic_compounds") || deviceClass.equals("aqi")) {
            return DeviceType.AIR_QUALITY_SENSOR;
        }
        // Fallback for a `sensor`-domain entity with NO device_class at all
        // (the Kidde CO-level entity, confirmed live) -- unit_of_measurement
        // is the only remaining signal HA's own convention guarantees. This
        // is deliberately generic (unit-based, not "if entity_id contains
        // kidde") so it also covers a future non-native integration with the
        // same vendor-side gap, not just this one device.
        if ("sensor".equals(domain)) {
            String unit = String.valueOf(attrs.getOrDefault("unit_of_measurement", "")).toLowerCase(Locale.ROOT);
            if ("ppm".equals(unit)) return DeviceType.CO_SENSOR;
        }
        return DeviceType.HOME_ASSISTANT_ENTITY;
    }

    /**
     * Reusable "3rd-party/non-native sensor onboarding" pattern (see
     * docs/ontology.yaml's smart_appliance_co_air_quality_kidde and the
     * cabin-3rd-party-device-onboarding skill): a native Zigbee2MQTT
     * device's payload already carries semantically-named fields
     * ("temperature", "humidity", ...) that WorkflowRuleService,
     * CabinEventService.dailyAggregates(), and the frontend's
     * SensorHistoryPanel all key off of directly. An HA-discovered entity's
     * only guaranteed reading is its raw `state` string (captured as
     * `value` in discoverLocation()) -- normalizing it into the SAME
     * semantic key here, keyed off the already-inferred DeviceType, means
     * every one of those downstream consumers treats a Kidde/Liebherr/etc.
     * reading exactly like a native one, with zero special-casing per
     * integration. Returns null for a type with no chartable numeric
     * reading (locks, cameras, ...), which callers must handle by skipping
     * the normalization rather than writing a null-valued field.
     */
    private String semanticFieldFor(DeviceType type) {
        return switch (type) {
            case TEMPERATURE_SENSOR -> "temperature";
            case HUMIDITY_SENSOR -> "humidity";
            case CO2_SENSOR -> "co2";
            case AIR_QUALITY_SENSOR -> "airQualityIndex";
            case CO_SENSOR -> "co";
            default -> null;
        };
    }

    private Set<DeviceCapability> inferCapabilities(String domain, DeviceType type) {
        Set<DeviceCapability> caps = new HashSet<>();
        caps.add(DeviceCapability.TELEMETRY);
        if (Set.of("lock", "climate", "switch", "light", "cover").contains(domain)) caps.add(DeviceCapability.COMMAND);
        if (type == DeviceType.LOCK) caps.add(DeviceCapability.ACCESS_CONTROL);
        if (type == DeviceType.THERMOSTAT) caps.add(DeviceCapability.CLIMATE);
        if (type == DeviceType.CAMERA) caps.add(DeviceCapability.STREAM);
        if (Set.of(DeviceType.SMOKE_ALARM, DeviceType.CO_ALARM, DeviceType.WATER_LEAK_SENSOR).contains(type)) caps.add(DeviceCapability.ALARM);
        if (type == DeviceType.MOTION_SENSOR) caps.add(DeviceCapability.PRESENCE);
        return caps;
    }
}
