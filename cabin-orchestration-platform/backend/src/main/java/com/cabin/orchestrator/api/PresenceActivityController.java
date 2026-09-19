package com.cabin.orchestrator.api;

import com.cabin.orchestrator.presence.PresenceActivityService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * GET /api/presence/activity -- per-sensor, per-day motion history for the
 * Security &amp; Presence view.
 *
 * <p>Already behind WebConfig's "/api/presence/**" Google gate (D14: the whole
 * prefix is occupancy signal, no read carve-out). On top of that it requires
 * ADMINISTRATOR or ADULT_HOUSEHOLD_MEMBER -- a day-by-day record of when a
 * room was in use is more than a guest link or a VIEWER-tier account needs,
 * the same reasoning as the platform-import records route.
 */
@RestController
@RequestMapping("/api/presence")
@CrossOrigin
public class PresenceActivityController {

    private final PresenceActivityService service;

    public PresenceActivityController(PresenceActivityService service) {
        this.service = service;
    }

    @GetMapping("/activity")
    public ResponseEntity<?> activity(HttpServletRequest request,
                                      @RequestParam(required = false) String deviceId,
                                      @RequestParam(required = false) String location,
                                      @RequestParam(required = false) Integer days,
                                      @RequestParam(required = false) Integer visitGapMinutes) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR && role != HouseholdRole.ADULT_HOUSEHOLD_MEMBER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "This role cannot view presence history"));
        }
        return ResponseEntity.ok(service.activity(deviceId, location, days, visitGapMinutes, Instant.now()));
    }
}
