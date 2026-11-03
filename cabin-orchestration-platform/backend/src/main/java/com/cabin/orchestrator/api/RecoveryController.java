package com.cabin.orchestrator.api;

import com.cabin.orchestrator.recovery.RecoveryHeartbeatService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * The Monitoring section's feed for the recovery check and component heartbeats. Administrator only, read only: it lists
 * the host's internal component names, heartbeat ages and install commands. The Critical banner does not use this route;
 * it gets the same conditions through GET /api/alerts/active (ActiveAlertService).
 */
@RestController
@RequestMapping("/api/system/recovery")
@CrossOrigin
public class RecoveryController {

    private final RecoveryHeartbeatService service;

    public RecoveryController(RecoveryHeartbeatService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> view(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only an administrator can see recovery status"));
        }
        return ResponseEntity.ok(service.view(Instant.now()));
    }
}
