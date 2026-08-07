package com.cabin.orchestrator.api;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@CrossOrigin
public class EventController {

    private final CabinEventService eventService;

    public EventController(CabinEventService eventService) {
        this.eventService = eventService;
    }

    /**
     * GET /api/events?camera=outdoor_4&limit=20&offset=0&window=10m&eventTypePrefix=DETECTION_,MOTION_
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
     */
    @GetMapping
    public List<CabinEvent> recentEvents(
            @RequestParam(name = "camera", required = false) String camera,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(name = "offset", required = false, defaultValue = "0") int offset,
            @RequestParam(name = "window", required = false, defaultValue = "24h") String window,
            @RequestParam(name = "eventTypePrefix", required = false) String eventTypePrefix) {
        Instant since = Instant.now().minus(parseWindow(window));
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        int cappedOffset = Math.max(offset, 0);
        List<String> prefixes = (eventTypePrefix == null || eventTypePrefix.isBlank())
            ? null
            : java.util.Arrays.stream(eventTypePrefix.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        return eventService.recent(camera, cappedLimit, cappedOffset, since, prefixes);
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
