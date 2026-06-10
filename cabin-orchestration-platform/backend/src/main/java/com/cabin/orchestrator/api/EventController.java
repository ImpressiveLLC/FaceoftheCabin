package com.cabin.orchestrator.api;

import com.cabin.orchestrator.events.CabinEvent;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/events")
@CrossOrigin
public class EventController {
    @GetMapping
    public List<CabinEvent> recentEvents() {
        return List.of(
            new CabinEvent("evt-demo-1", "system", "SYSTEM_READY", "INFO", Instant.now(), Map.of("message", "Cabin orchestrator online"))
        );
    }
}
