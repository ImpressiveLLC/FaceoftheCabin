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
     * GET /api/events?camera=outdoor_4&limit=20&window=10m
     * Unauthenticated, same precedent as /api/devices — this is the "at
     * minimum, push events to FaceoftheCabin" tier. camera/limit/window are
     * all optional; defaults to the last 24h, 20 events, any camera/device.
     * window accepts a trailing unit: s/m/h/d (e.g. "10m", "24h").
     */
    @GetMapping
    public List<CabinEvent> recentEvents(
            @RequestParam(name = "camera", required = false) String camera,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @RequestParam(name = "window", required = false, defaultValue = "24h") String window) {
        Instant since = Instant.now().minus(parseWindow(window));
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        return eventService.recent(camera, cappedLimit, since);
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
