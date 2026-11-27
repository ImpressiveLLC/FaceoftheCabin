package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.monitoring.MonitoringPreferences;
import com.cabin.orchestrator.devices.monitoring.MonitoringPreferenceService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/devices/monitoring-preferences")
@CrossOrigin
public class MonitoringPreferenceController {
    private final MonitoringPreferenceService service;

    public MonitoringPreferenceController(MonitoringPreferenceService service) { this.service = service; }

    @GetMapping
    public MonitoringPreferences read(@RequestParam String location) { return service.read(location); }

    public record Update(Long expectedRevision, List<MonitoringPreferences.Card> cards) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown preference field: " + name);
        }
    }

    /** The existing auth interceptor rejects demo/guest/viewer writes before this handler. */
    @PatchMapping
    public MonitoringPreferences replace(@RequestParam String location, @RequestBody Update update,
                                         HttpServletRequest request) {
        Object identity = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        if (!(identity instanceof String actor) || actor.isBlank())
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign-in required");
        if (update.expectedRevision() == null || update.expectedRevision() < 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nonnegative expectedRevision required");
        return service.replace(location, update.expectedRevision(), update.cards(), actor);
    }
}
