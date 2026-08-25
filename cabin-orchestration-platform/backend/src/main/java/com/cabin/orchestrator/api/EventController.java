package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.events.TelemetryDailyPoint;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/events")
@CrossOrigin
public class EventController {

    private final CabinEventService eventService;
    private final DeviceRegistry registry;

    public EventController(CabinEventService eventService, DeviceRegistry registry) {
        this.eventService = eventService;
        this.registry = registry;
    }

    /**
     * GET /api/events?camera=outdoor_4&limit=20&offset=0&window=10m&eventTypePrefix=DETECTION_,MOTION_&location=home
     * Unauthenticated, same precedent as /api/devices — this is the "at
     * minimum, push events to FaceoftheCabin" tier. All params are
     * optional; defaults to the last 24h, 20 events, offset 0, any
     * camera/device, no eventType filter. window accepts a trailing unit:
     * s/m/h/d (e.g. "10m", "24h").
     *
     * eventTypePrefix and offset added 2026-08-07 (Phase 7 §4a/§4c): the
     * real server-side fix for CameraEventsPanel filtering client-side
     * (isCameraEvent, App.jsx) and being capped at the most recent 30 with
     * no way to page further back — see docs/ontology.yaml's
     * cabin_camera_event entry.
     *
     * location added 2026-08-15: cabin_event has no location column of its
     * own (events are saved from many call sites, not just camera MQTT —
     * adding one there would touch all of them for one narrow need), so
     * this filters post-fetch by joining each event's sourceDeviceId
     * against DeviceRegistry.byLocation() instead. Exists specifically so
     * a location whose own backend isn't deployed yet (see
     * cabin-orchestration-platform/locations/home/, still an undeployed
     * scaffold) can still show its devices' events by querying the
     * backend that's actually processing them, filtered down to just that
     * location — see App.jsx's CameraEventsPanel for the caller.
     */
    @GetMapping
    public List<CabinEvent> recentEvents(
            @RequestParam(name = "camera", required = false) String camera,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(name = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(name = "window", required = false, defaultValue = "24h") String window,
            @RequestParam(name = "eventTypePrefix", required = false) String eventTypePrefix,
            @RequestParam(name = "location", required = false) String location) {
        Instant since = Instant.now().minus(parseWindow(window));
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int cappedOffset = Math.max(offset, 0);
        List<String> prefixes = (eventTypePrefix == null || eventTypePrefix.isBlank())
            ? null
            : java.util.Arrays.stream(eventTypePrefix.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        // Over-fetch before applying the location join so pagination still
        // makes sense once devices outside the requested location are
        // dropped -- the alternative (filter after applying limit/offset)
        // would silently return fewer than `limit` results, or an empty
        // page, whenever any non-matching devices exist in that window.
        if (location != null && !location.isBlank()) {
            Set<String> deviceIdsInLocation = registry.byLocation(location).stream()
                .map(d -> d.deviceId()).collect(Collectors.toSet());
            List<CabinEvent> overFetched = eventService.recent(
                camera, Math.min(cappedLimit + cappedOffset + 200, 500), 0, since, prefixes);
            return overFetched.stream()
                .filter(e -> deviceIdsInLocation.contains(e.sourceDeviceId()))
                .skip(cappedOffset)
                .limit(cappedLimit)
                .toList();
        }
        return eventService.recent(camera, cappedLimit, cappedOffset, since, prefixes);
    }

    /**
     * GET /api/events/telemetry-history?deviceId=z2m-temp_mech_room&field=humidity&days=30
     * Day-bucketed min/avg/max for one numeric TELEMETRY payload field on
     * one device -- built for a real historical trend view (Monitoring
     * panel's "History" section), not raw event replay: this controller's
     * own recentEvents() caps at 200 rows, which can't cover weeks of
     * ~10-15min-interval readings. Unauthenticated, same precedent as
     * every other GET here.
     */
    @GetMapping("/telemetry-history")
    public List<TelemetryDailyPoint> telemetryHistory(
            @RequestParam(name = "deviceId") String deviceId,
            @RequestParam(name = "field") String field,
            @RequestParam(name = "days", required = false, defaultValue = "30") int days) {
        return eventService.dailyAggregates(deviceId, field, days);
    }

    private Duration parseWindow(String window) {
        try {
            char unit = window.charAt(window.length() - 1);
            long value = Long.parseLong(window.substring(0, window.length() - 1));
            return switch (unit) {
                case 's' -> Duration.ofSeconds(value);
                case 'm' -> Duration.ofMinutes(value);
                case 'h' -> Duration.ofHours(value);
                case 'd' -> Duration.ofDays(value);
                default -> Duration.ofHours(24);
            };
        } catch (Exception e) {
            return Duration.ofHours(24);
        }
    }
}
