package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.AlertSeverityClassifier;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 2026-08-15: Frigate's own event database is the durable system of record
 * for detection clips/snapshots -- MQTT is a low-latency hint that
 * something just happened, not a reliable delivery channel for it.
 * Confirmed live, not assumed: Frigate 0.17.2 publishes cabin/camera/events
 * at MQTT QoS 0 (its own mqtt config, read directly), and a subscriber's
 * own QoS/persistent-session settings (see MqttBridgeService.connect())
 * cannot upgrade a QoS-0 publication into guaranteed queued delivery --
 * that's not how MQTT QoS negotiation works, it's the minimum of publisher
 * and subscriber QoS that governs actual delivery. Real, valid, has_clip
 * detections from this exact system (2026-08-13, 2026-08-08) never once
 * reached cabin_event -- zero trace in 72h of backend logs -- which is
 * consistent with the backend simply not being the one connected client at
 * the instant each relatively rare detection fired, something this
 * project's own frequent-deploy/restart cadence makes more likely, not
 * less. QoS 1 + a stable client id (see MqttBridgeService's own comment)
 * is still worth having as defense in depth for the *other* QoS-1
 * subscriptions this bridge holds, but it does not close this specific gap
 * on its own.
 *
 * This service instead reconciles directly against Frigate's REST API
 * (GET /api/events, documented at
 * https://docs.frigate.video/integrations/api/events-search-events-search-get/),
 * which reflects Frigate's own durable event database regardless of
 * whether any MQTT message about it was ever received. MQTT still matters
 * for latency -- MqttBridgeService.handleFrigateDetectionEvent() calls the
 * same upsertDetection() this class does, so a connected, in-sync backend
 * shows a new detection within moments, not up to a minute later. This
 * class is what guarantees eventual correctness regardless of whether that
 * happened.
 */
@Service
public class FrigateEventReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(FrigateEventReconciliationService.class);

    private final String frigateUrl;
    private final int backfillDays;
    private final int overlapSeconds;
    private final int pageLimit;

    private final CabinEventService eventService;
    private final DeviceRegistry registry;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // Health state, read by GET /api/camera/reconciliation/status -- this
    // process runs unattended and silently; without this, "is it actually
    // working" would only ever be answerable by grepping logs.
    private volatile Instant lastRunAt;
    private volatile Instant lastSuccessAt;
    private volatile Instant mostRecentFrigateEventSeenAt;
    private volatile int lastUpsertedCount;
    private volatile String lastError;
    private volatile double cursorEpochSeconds;

    // @Value on constructor params (not fields) -- same pattern as
    // NtfyAlertPublisher's own constructor, and for the same reason: it's
    // what lets a test point frigateUrl at a local fake server instead of
    // needing a real Frigate instance or a Spring context to exercise the
    // actual HTTP/parsing/upsert logic in reconcileSince().
    public FrigateEventReconciliationService(
            CabinEventService eventService, DeviceRegistry registry,
            @Value("${cabin.devices.cameras.frigateUrl:http://frigate:5000}") String frigateUrl,
            // Matches this deployment's own confirmed Frigate retention
            // (continuous.days: 5, read live from /api/config) --
            // backfilling further back than Frigate itself retains would
            // just return nothing for the extra range.
            @Value("${cabin.devices.cameras.reconciliation.backfillDays:5}") int backfillDays,
            // Re-queries this far behind the last-seen event's own
            // start_time on every incremental run, not just from where the
            // last run left off -- absorbs clock drift between this host
            // and Frigate's, and means one failed/partial run can't
            // quietly open a permanent gap: the next successful run
            // re-covers the overlap and re-upserts (idempotently) anything
            // it already had.
            @Value("${cabin.devices.cameras.reconciliation.overlapSeconds:300}") int overlapSeconds,
            @Value("${cabin.devices.cameras.reconciliation.pageLimit:200}") int pageLimit) {
        this.eventService = eventService;
        this.registry = registry;
        this.frigateUrl = frigateUrl;
        this.backfillDays = backfillDays;
        this.overlapSeconds = overlapSeconds;
        this.pageLimit = pageLimit;
    }

    @PostConstruct
    public void initialBackfill() {
        double since = Instant.now().minus(Duration.ofDays(backfillDays)).getEpochSecond();
        log.info("Frigate event reconciliation: initial backfill from {} ({} day window)",
            Instant.ofEpochSecond((long) since), backfillDays);
        reconcileSince(since);
    }

    // Same 60s cadence DeviceHealthMonitor already uses elsewhere in this
    // codebase. initialBackfill() above already ran once at startup by the
    // time this first fires -- cursorEpochSeconds is never stale-zero here.
    @Scheduled(fixedDelay = 60_000)
    public void periodicReconcile() {
        double since = Math.max(0, cursorEpochSeconds - overlapSeconds);
        reconcileSince(since);
    }

    private synchronized void reconcileSince(double sinceEpochSeconds) {
        lastRunAt = Instant.now();
        int upserted = 0;
        try {
            String url = frigateUrl + "/api/events?after=" + (long) sinceEpochSeconds + "&limit=" + pageLimit;
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                lastError = "Frigate /api/events returned " + resp.statusCode();
                log.warn(lastError);
                return;
            }
            JsonNode events = mapper.readTree(resp.body());
            double newestSeen = cursorEpochSeconds;
            for (JsonNode event : events) {
                upsertFromRestEvent(event);
                upserted++;
                double startTime = event.path("start_time").asDouble(0);
                if (startTime > newestSeen) newestSeen = startTime;
            }
            cursorEpochSeconds = newestSeen;
            if (newestSeen > 0) mostRecentFrigateEventSeenAt = Instant.ofEpochSecond((long) newestSeen);
            lastUpsertedCount = upserted;
            lastSuccessAt = Instant.now();
            lastError = null;
            if (upserted > 0) {
                log.info("Frigate event reconciliation: upserted {} event(s), cursor now {}",
                    upserted, mostRecentFrigateEventSeenAt);
            }
        } catch (IOException | InterruptedException e) {
            lastError = "Failed to reach Frigate: " + e.getMessage();
            log.warn(lastError);
        } catch (Exception e) {
            lastError = "Reconciliation failed: " + e.getMessage();
            log.error(lastError, e);
        }
    }

    private void upsertFromRestEvent(JsonNode event) {
        String frigateEventId = event.path("id").asText(null);
        String camera = event.path("camera").asText("unknown");
        String label = event.path("label").asText("object");
        boolean hasSnapshot = event.path("has_snapshot").asBoolean(false);
        boolean hasClip = event.path("has_clip").asBoolean(false);
        double startTime = event.path("start_time").asDouble(0);
        double score = event.path("data").path("score").asDouble(
            event.path("top_score").asDouble(0));
        Instant occurredAt = startTime > 0 ? Instant.ofEpochMilli((long) (startTime * 1000)) : Instant.now();
        // The full REST event object, not just the fields this method
        // otherwise extracts -- same reasoning as the rawAttributes param
        // on upsertDetection() below: AlertSeverityClassifier should see
        // whatever Frigate actually sent (zones, sub_label, false_positive,
        // etc.), not just the handful of fields this service happens to
        // curate into eventPayload.
        Map<String, Object> rawAttributes = mapper.convertValue(event, new TypeReference<Map<String, Object>>() {});
        // REST is a snapshot of current state, not a new/update/end stream
        // like MQTT -- "UPDATE" is the honest label for "here's what this
        // detection currently looks like," not a claim about lifecycle
        // stage.
        upsertDetection(frigateEventId, camera, label, score, hasSnapshot, hasClip, occurredAt, "update", rawAttributes);
    }

    /**
     * The one write path both MqttBridgeService (immediate, best-effort)
     * and this class (delayed, authoritative) call -- same deterministic
     * id, same upsert semantics, so whichever gets there first doesn't
     * matter and neither can leave stale data the other would have
     * corrected. Also registers/touches the source camera in
     * DeviceRegistry with the same home_/cabin location convention
     * MqttBridgeService.deriveCameraLocation() uses, so a device only ever
     * seen via REST reconciliation (MQTT connectivity gap, cold start,
     * etc.) still gets correctly location-tagged for
     * GET /api/events?location= -- not just the CabinEvent row itself.
     *
     * rawAttributes feeds AlertSeverityClassifier only -- it's whatever the
     * calling source actually has available (MQTT's own "after" object, or
     * the REST event's full field set), not necessarily the same shape as
     * eventPayload below (which stays a small, stable, UI-facing subset
     * regardless of source). Passing the caller's real attributes through
     * -- rather than a fixed/empty map -- is what lets the classifier see
     * a field like "alarm" if Frigate (or a future detector) ever sends
     * one, matching the classifier's original design intent instead of
     * silently disconnecting it. Nullable; treated as empty (-> INFO for
     * every field this classifier currently knows about, which matches
     * today's real Frigate payloads either way).
     */
    void upsertDetection(String frigateEventId, String camera, String label, double score,
                          boolean hasSnapshot, boolean hasClip, Instant occurredAt, String detectionType,
                          Map<String, Object> rawAttributes) {
        if (frigateEventId == null || frigateEventId.isBlank()) {
            log.debug("Skipping detection with no Frigate event id (camera={})", camera);
            return;
        }
        touchCameraDevice(camera);

        Map<String, Object> eventPayload = new HashMap<>();
        eventPayload.put("label", label);
        eventPayload.put("score", score);
        eventPayload.put("type", detectionType);
        eventPayload.put("frigateEventId", frigateEventId);
        eventPayload.put("hasSnapshot", hasSnapshot);
        eventPayload.put("hasClip", hasClip);

        String severity = AlertSeverityClassifier.classify(rawAttributes == null ? Map.of() : rawAttributes);

        CabinEvent event = new CabinEvent(
            "frigate:" + frigateEventId, camera,
            "DETECTION_" + detectionType.toUpperCase(),
            severity, occurredAt, eventPayload);
        eventService.upsert(event);
    }

    // Mirrors MqttBridgeService.touchCamera()'s own registration exactly
    // (same DeviceType/DeviceCapability/location derivation) -- kept as a
    // separate copy rather than a shared extraction, since touchCamera()
    // also handles the two non-detection camera topics (motion, per-label
    // count) this class has no reason to know about; duplicating one
    // small, stable registration block here is more contained than adding
    // a shared dependency PurelyForThis between two services that
    // otherwise don't need to know about each other's topic-shape details.
    private void touchCameraDevice(String cameraId) {
        DeviceStatus existing = registry.get(cameraId);
        String location = cameraId.startsWith("home_") ? "home" : "cabin";
        if (existing == null) {
            DeviceDescriptor descriptor = new DeviceDescriptor(cameraId, cameraId, DeviceType.CAMERA,
                Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "mqtt",
                "cabin/camera/" + cameraId, false, location);
            registry.registerCandidate(descriptor, Map.of("discoveredFrom", "Frigate REST reconciliation"));
            DeviceStatus candidate = registry.get(cameraId);
            registry.update(new DeviceStatus(cameraId, DeviceType.CAMERA, cameraId, "ONLINE",
                Instant.now(), candidate.attributes(), location));
            return;
        }
        registry.update(new DeviceStatus(existing.deviceId(), existing.type(), existing.name(),
            "ONLINE", Instant.now(), existing.attributes(), existing.location()));
    }

    public Map<String, Object> healthStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("lastRunAt", lastRunAt);
        status.put("lastSuccessAt", lastSuccessAt);
        status.put("mostRecentFrigateEventSeenAt", mostRecentFrigateEventSeenAt);
        status.put("lagSeconds", mostRecentFrigateEventSeenAt == null
            ? null : Duration.between(mostRecentFrigateEventSeenAt, Instant.now()).getSeconds());
        status.put("lastUpsertedCount", lastUpsertedCount);
        status.put("lastError", lastError);
        status.put("healthy", lastError == null && lastSuccessAt != null);
        return status;
    }
}
