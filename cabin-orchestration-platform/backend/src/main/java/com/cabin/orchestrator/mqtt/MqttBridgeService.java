package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.events.AlertSeverityClassifier;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Subscribes to all cabin/device/# and cabin/camera/# MQTT topics.
 * Routes telemetry → DeviceRegistry.update() and → Kafka cabin.events.raw.
 *
 * Topic contract (from docs/device-topic-contract.md):
 *   cabin/device/{deviceId}/state      — full state snapshot
 *   cabin/device/{deviceId}/telemetry  — metric payload (psi, temp, etc.)
 *   cabin/event/{severity}             — direct event injection
 *   cabin/camera/{cameraId}/motion     — Frigate motion events
 *   cabin/system/health                — watchdog health reports
 *   {location}/presence/{personId}     — "home"/"not_home", per-person
 *                                         WiFi presence (see
 *                                         handlePresenceTopic) — one of two
 *                                         subscriptions here that isn't
 *                                         cabin/-prefixed, deliberately:
 *                                         PresenceService's derivation
 *                                         needs signals from every
 *                                         location this instance manages,
 *                                         not just cabin's own.
 *   {location}/security/armed_away     — "ON"/"OFF", real HA-published,
 *                                         retained, self-healing armed
 *                                         state (see handleArmedTopic) —
 *                                         the other location-agnostic
 *                                         subscription, same reasoning.
 */
@Service
public class MqttBridgeService implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttBridgeService.class);

    @Value("${cabin.mqtt.brokerUrl:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${cabin.mqtt.clientId:cabin-orchestrator}")
    private String clientId;

    private MqttClient client;
    private final DeviceRegistry registry;
    private final EventPublisher eventPublisher;
    private final PresenceService presenceService;
    private final PresenceSignalRegistry presenceSignalRegistry;
    private final SecurityStateRegistry securityStateRegistry;
    private final FrigateEventReconciliationService frigateReconciliation;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public MqttBridgeService(DeviceRegistry registry, EventPublisher eventPublisher,
                              PresenceService presenceService, PresenceSignalRegistry presenceSignalRegistry,
                              SecurityStateRegistry securityStateRegistry,
                              FrigateEventReconciliationService frigateReconciliation) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
        this.presenceService = presenceService;
        this.presenceSignalRegistry = presenceSignalRegistry;
        this.securityStateRegistry = securityStateRegistry;
        this.frigateReconciliation = frigateReconciliation;
    }

    @PostConstruct
    public void connect() {
        try {
            // 2026-08-15: was clientId + "-" + UUID.randomUUID() -- a fresh
            // random suffix on every connect, which silently defeated
            // setCleanSession(false) below. A persistent MQTT session (QoS
            // 1/2 messages queued by the broker while this client is briefly
            // offline) is keyed on the exact client id staying the same
            // across reconnects/restarts; a new id each time means the
            // broker sees a brand-new client with no session to resume,
            // every single time. Stable id now, so the persistent-session
            // machinery actually does something -- though see
            // FrigateEventReconciliationService's own comment for why this
            // alone still isn't sufficient for Frigate's detection events
            // specifically (those publish at QoS 0 on Frigate's end; a
            // subscriber's QoS doesn't retroactively upgrade a publisher's).
            client = new MqttClient(brokerUrl, clientId);
            client.setCallback(this);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            client.connect(opts);
            client.subscribe("cabin/device/#", 1);
            client.subscribe("cabin/camera/#", 1);
            client.subscribe("cabin/event/#", 1);
            client.subscribe("cabin/system/#", 0);
            // Wildcarded on location (+), not hardcoded to cabin/ -- see
            // this class's javadoc. Only cabin/presence/nate exists live
            // today (home-hub isn't deployed yet), but this subscription
            // and everything downstream of it (PresenceSignalRegistry,
            // PresenceService.recomputeFromSignals) already treat
            // location as data, not an assumption, so a future
            // home/presence/{anyone} publisher needs zero backend changes
            // to be picked up.
            client.subscribe("+/presence/#", 1);
            // Same wildcard reasoning as presence above. Only
            // cabin/security/armed_away is real today (home-hub isn't
            // deployed), but nothing downstream (SecurityStateRegistry,
            // the /api/security endpoint) assumes cabin-only.
            client.subscribe("+/security/armed_away", 1);
            log.info("MQTT bridge connected to {}", brokerUrl);
        } catch (MqttException e) {
            log.error("MQTT connect failed: {}", e.getMessage());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload());
            log.debug("MQTT message arrived: topic={} payload={}", topic, payload);
            String[] parts = topic.split("/");

            if (parts.length >= 3 && "camera".equals(parts[1])) {
                // Frigate's camera/# topics are NOT uniformly JSON — `available`
                // and `{camera}/motion` are plain text ("online"/"ON"), only
                // `events` is JSON. Parsing happens per-branch below, not here.
                handleCameraTopic(parts, payload);
                return;
            }

            if (parts.length == 3 && "presence".equals(parts[1])) {
                // Plain text ("home"/"not_home"), same reasoning as camera
                // topics above -- must be handled before the generic
                // JSON-parse fallback below, not through it.
                handlePresenceTopic(parts, payload);
                return;
            }

            if (parts.length == 3 && "security".equals(parts[1]) && "armed_away".equals(parts[2])) {
                // Plain text ("ON"/"OFF"), same reasoning as presence above.
                handleArmedTopic(parts[0], payload);
                return;
            }

            Map<String, Object> data = mapper.readValue(payload, Map.class);
            if (parts.length >= 3 && "device".equals(parts[1])) {
                String deviceId = parts[2];
                String msgType = parts.length >= 4 ? parts[3] : "state";
                handleDeviceMessage(deviceId, msgType, data);
            } else if (parts.length >= 3 && "event".equals(parts[1])) {
                handleDirectEvent(parts[2], data);
            }
        } catch (Exception e) {
            log.warn("Failed to process MQTT message on {}: {}", topic, e.getMessage());
        }
    }

    private void handleDeviceMessage(String deviceId, String msgType, Map<String, Object> data) {
        DeviceStatus existing = registry.get(deviceId);
        if (existing == null) {
            // Auto-register unknown device — type inferred from payload keys, location from topic prefix
            DeviceType type = inferType(data);
            String loc = deviceId.startsWith("home-") ? "home" : "cabin";
            DeviceDescriptor descriptor = new DeviceDescriptor(deviceId, deviceId, type,
                inferCapabilities(data), "mqtt", loc + "/device/" + deviceId, false, loc);
            registry.registerCandidate(descriptor, Map.of("discoveredFrom", "MQTT topic"));
            existing = registry.get(deviceId);
            log.info("Auto-registered new device: {} as {} at {}", deviceId, type, loc);
        }
        Map<String, Object> attrs = new LinkedHashMap<>(existing.attributes());
        attrs.putAll(data);
        String state = determineState(data, existing.type());
        registry.update(new DeviceStatus(deviceId, existing.type(), existing.name(), state,
            Instant.now(), attrs, existing.location()));

        // Publish to Kafka for rules engine consumption
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), deviceId, "TELEMETRY",
            AlertSeverityClassifier.classify(data), Instant.now(), data);
        eventPublisher.publish(event);
    }

    private Set<DeviceCapability> inferCapabilities(Map<String, Object> data) {
        Set<DeviceCapability> capabilities = new HashSet<>();
        capabilities.add(DeviceCapability.TELEMETRY);
        if (data.containsKey("locked")) {
            capabilities.add(DeviceCapability.COMMAND);
            capabilities.add(DeviceCapability.ACCESS_CONTROL);
        }
        if (data.containsKey("alarm") || data.containsKey("smoke") || data.containsKey("co")) {
            capabilities.add(DeviceCapability.ALARM);
        }
        if (data.containsKey("motion") || data.containsKey("occupancy") || data.containsKey("presence")) {
            capabilities.add(DeviceCapability.PRESENCE);
        }
        return capabilities;
    }

    // Frigate's real topic shapes under cabin/camera/ (confirmed against a
    // live Frigate deployment 2026-08-02, not assumed from docs alone):
    //   cabin/camera/available          -- "online"/"offline", plain text
    //   cabin/camera/{name}/motion      -- "ON"/"OFF", plain text
    //   cabin/camera/{name}/{label}     -- object count, plain text number
    //   cabin/camera/events             -- the rich JSON detection stream;
    //                                       camera/label live INSIDE the
    //                                       payload, not the topic path
    // The previous version of this bridge treated whatever followed
    // cabin/camera/ as a camera ID and JSON-parsed every payload — wrong
    // for all four of these (parts[2]=="events" isn't a camera name, and
    // three of the four payloads aren't JSON at all).
    //
    // Found 2026-08-07: none of these branches ever called
    // registry.update() for the camera itself -- only handleDeviceMessage
    // (cabin/device/#) did. A camera's DeviceStatus.lastSeen was therefore
    // set once (whenever/however it first got registered) and never
    // refreshed, so DeviceHealthMonitor's 5-minute camera stale threshold
    // always fired exactly 5 minutes after that one registration and the
    // camera could never recover -- matching the reported "loads, then
    // disappears after ~5 min" symptom. Motion and per-label count topics
    // both name a real camera and both fire far more often than every 5
    // minutes whenever Frigate is actually seeing frames, so either is a
    // good liveness signal; touchCamera() is called from both.
    private void handleCameraTopic(String[] parts, String payload) {
        if (parts.length == 3 && "events".equals(parts[2])) {
            handleFrigateDetectionEvent(payload);
        } else if (parts.length == 4 && "motion".equals(parts[3])) {
            String cameraId = parts[2];
            touchCamera(cameraId);
            String state = "ON".equalsIgnoreCase(payload.trim()) ? "MOTION_ON" : "MOTION_OFF";
            eventPublisher.publish(new CabinEvent(
                UUID.randomUUID().toString(), cameraId, state,
                "INFO", Instant.now(), Map.of("camera", cameraId)));
        } else if (parts.length == 4) {
            // per-label object-count topic, e.g. cabin/camera/driveway/car —
            // not published as a CabinEvent (fires too often to be useful,
            // see below), but still real evidence the camera is alive.
            touchCamera(parts[2]);
        }
        // `available` is deliberately still not handled here: it's
        // Frigate's single bridge-wide topic (parts.length==2), doesn't
        // name any one camera, and per-label count topics still aren't
        // published as CabinEvents — they'd mirror the JSON stream
        // cabin/camera/events already sends and fire far too often to be
        // useful "events", even though they're useful for touchCamera().
    }

    /**
     * Marks a camera as seen right now — auto-registers it (same pattern
     * handleDeviceMessage uses for cabin/device/# devices) if this is the
     * first time this camera ID has appeared, otherwise just refreshes
     * lastSeen/state on the existing entry without touching its other
     * attributes.
     */
    private void touchCamera(String cameraId) {
        DeviceStatus existing = registry.get(cameraId);
        if (existing == null) {
            String location = deriveCameraLocation(cameraId);
            DeviceDescriptor descriptor = new DeviceDescriptor(cameraId, cameraId, DeviceType.CAMERA,
                Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "mqtt",
                "cabin/camera/" + cameraId, false, location);
            registry.registerCandidate(descriptor, Map.of("discoveredFrom", "Frigate MQTT"));
            DeviceStatus candidate = registry.get(cameraId);
            registry.update(new DeviceStatus(cameraId, DeviceType.CAMERA, cameraId, "ONLINE",
                Instant.now(), candidate.attributes(), location));
            log.info("Auto-registered new camera: {} (location={})", cameraId, location);
            return;
        }
        registry.update(new DeviceStatus(existing.deviceId(), existing.type(), existing.name(),
            "ONLINE", Instant.now(), existing.attributes(), existing.location()));
    }

    /**
     * Cameras all arrive on the same cabin/camera/# MQTT subscription
     * regardless of where they physically live (2026-08-15: AldrichFront
     * is a Home-location Blink camera relayed through the cabin M920q's
     * own blinkbridge/Frigate, on the same Blink account/sync module as
     * the cabin's driveway camera -- there is no separate home-location
     * MQTT broker or backend instance for it to arrive on instead). The
     * camera id's home_ prefix is the only signal available here for
     * which location it actually belongs to.
     */
    private String deriveCameraLocation(String cameraId) {
        return cameraId.startsWith("home_") ? "home" : "cabin";
    }

    /**
     * {location}/presence/{personId} -- plain text "home"/"not_home",
     * same payload convention the underlying HA automation already
     * publishes (see docs/ontology.yaml's automation_cabin_security_
     * publish_nate_presence_from_phone). location comes from the topic
     * itself, not a constant -- this is what makes presence derivation
     * N-people x M-locations rather than assuming a single person at a
     * single site. Every message here both records the raw signal and
     * immediately re-derives the aggregate PresenceProfile, so the
     * toolbar's presence pin reflects reality within one MQTT round trip
     * of a phone joining or leaving the WiFi, not just whatever was last
     * manually picked.
     */
    private void handlePresenceTopic(String[] parts, String payload) {
        String location = parts[0];
        String personId = parts[2];
        boolean present = "home".equalsIgnoreCase(payload.trim());
        presenceSignalRegistry.record(location, personId, present);
        presenceService.recomputeFromSignals();
        // Added 2026-08-21 -- this only ever updated PresenceSignalRegistry
        // before, so WorkflowRuleService (which only reacts to real
        // CabinEvents) had no way to see a presence change at all, real or
        // otherwise. Additive only: the two calls above are untouched, so
        // the toolbar presence pin/PresenceProfile derivation this feeds
        // keeps working exactly as before this line existed.
        // sourceDeviceId is a synthetic per-person id (not a real
        // DeviceRegistry entry) -- presence signals aren't devices, but
        // WorkflowRuleService.handleTriggerMatch()'s optional
        // triggerDeviceId scoping still needs something stable to key on.
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), "presence:" + location + ":" + personId, "PRESENCE_CHANGED",
            "INFO", Instant.now(), Map.of("present", present, "personId", personId, "location", location)));
    }

    /**
     * {location}/security/armed_away -- plain text "ON"/"OFF", published
     * by a real HA automation (see docs/ontology.yaml's automation_
     * cabin_security_publish_arm_state) that republishes on every toggle
     * AND on every HA restart, so this is already self-healing on the
     * publisher side -- cabin-backend just needs to actually listen.
     * Found 2026-08-08: nothing here ever did, despite this being exactly
     * the kind of "is this actually armed" answer a user asking about an
     * ambiguous alert needs and couldn't get from the UI at all.
     */
    private void handleArmedTopic(String location, String payload) {
        boolean armed = "ON".equalsIgnoreCase(payload.trim());
        securityStateRegistry.record(location, armed);
        // Added 2026-08-21 -- same reasoning as handlePresenceTopic()'s own
        // new publish call: this only ever updated SecurityStateRegistry
        // before, invisible to WorkflowRuleService. Additive only, the
        // record() call above (and the toolbar SecurityBadge it feeds) is
        // untouched. Real risk considered, not just assumed safe: this
        // topic republishes on every HA restart, not just a real toggle
        // (see this method's own doc comment) -- a repeat "armed" message
        // with no real state change can't cause a spurious re-fire storm
        // because trigger_security_armed's edge-detection (findActive() in
        // WorkflowRuleService.handleTriggerMatch()) already suppresses a
        // repeat detected-side match while a workflow's execution is still
        // active, the same protection trigger_water_leak_detected already
        // relies on for repeated telemetry.
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), "security:" + location, "SECURITY_ARMED_CHANGED",
            "INFO", Instant.now(), Map.of("armed", armed, "location", location)));
    }

    /**
     * 2026-08-15: no longer builds/publishes its own CabinEvent. Frigate
     * publishes this topic at its own QoS 0 with no redelivery guarantee --
     * whatever arrives here is a best-effort, low-latency hint that
     * something just happened, not something this backend can treat as the
     * only chance to record it. FrigateEventReconciliationService.
     * upsertDetection() is the single write path both this handler and that
     * service's own REST-backed periodic reconciliation call, keyed on
     * Frigate's own event id (not a fresh random UUID per message) so
     * whichever of the two sees a given detection first doesn't matter and
     * neither can leave stale hasClip/hasSnapshot data the other would have
     * corrected. See that class's javadoc for the full rationale.
     */
    @SuppressWarnings("unchecked")
    private void handleFrigateDetectionEvent(String payload) {
        try {
            Map<String, Object> data = mapper.readValue(payload, Map.class);
            String type = String.valueOf(data.getOrDefault("type", "unknown"));
            Map<String, Object> after = (Map<String, Object>) data.getOrDefault("after", Map.of());
            String camera = String.valueOf(after.getOrDefault("camera", "unknown"));
            String label = String.valueOf(after.getOrDefault("label", "object"));
            // Frigate's own event id (TrackedObject.to_dict()'s "id" field) --
            // required both to fetch that specific event's snapshot/clip via
            // Frigate's /api/events/{id}/... endpoints and to build the
            // deterministic frigate:{id} CabinEvent id upsertDetection()
            // uses. Without one there's nothing to key an upsert on and
            // nothing to ever fetch a clip for, so such a message (seen on
            // Frigate's intermediate "new" event states in practice) isn't
            // useful to record at all.
            Object frigateEventId = after.get("id");
            if (frigateEventId == null) {
                log.debug("Frigate detection event has no id, skipping: {}", payload);
                return;
            }
            double score = after.get("score") instanceof Number n ? n.doubleValue() : 0;
            boolean hasSnapshot = Boolean.TRUE.equals(after.get("has_snapshot"));
            boolean hasClip = Boolean.TRUE.equals(after.get("has_clip"));
            // Frigate's own start_time (unix epoch seconds, fractional) --
            // when the detection actually happened, not whenever this
            // backend happened to be connected and receive the message.
            Object startTime = after.get("start_time");
            Instant occurredAt = startTime instanceof Number n
                ? Instant.ofEpochMilli((long) (n.doubleValue() * 1000)) : Instant.now();
            frigateReconciliation.upsertDetection(String.valueOf(frigateEventId), camera, label, score,
                hasSnapshot, hasClip, occurredAt, type, after);
        } catch (Exception e) {
            log.warn("Failed to parse Frigate detection event: {}", e.getMessage());
        }
    }

    private void handleDirectEvent(String severity, Map<String, Object> data) {
        String deviceId = String.valueOf(data.getOrDefault("deviceId", "system"));
        String eventType = String.valueOf(data.getOrDefault("event", "UNKNOWN"));
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), deviceId, eventType,
            severity.toUpperCase(), Instant.now(), data);
        eventPublisher.publish(event);
    }

    private DeviceType inferType(Map<String, Object> data) {
        if (data.containsKey("psi"))     return DeviceType.WATER_PRESSURE_SENSOR;
        if (data.containsKey("temp_f"))  return DeviceType.TEMPERATURE_SENSOR;
        if (data.containsKey("alarm"))   return DeviceType.SMOKE_ALARM;
        if (data.containsKey("locked"))  return DeviceType.LOCK;
        if (data.containsKey("motion"))  return DeviceType.MOTION_SENSOR;
        return DeviceType.HOME_ASSISTANT_ENTITY;
    }

    private String determineState(Map<String, Object> data, DeviceType type) {
        Object alarm = data.get("alarm");
        if (Boolean.TRUE.equals(alarm)) return "ALARM";
        return "ONLINE";
    }

    @Override public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause.getMessage());
    }

    @Override public void deliveryComplete(IMqttDeliveryToken token) {}

    @PreDestroy
    public void disconnect() {
        try { if (client != null && client.isConnected()) client.disconnect(); }
        catch (MqttException ignored) {}
    }
}
