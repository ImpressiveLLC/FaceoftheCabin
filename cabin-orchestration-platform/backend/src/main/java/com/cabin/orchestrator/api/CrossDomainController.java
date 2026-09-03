package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.alerts.ActiveAlertsSnapshot;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * First real cross-domain route (cabin sensor state -> family scheduling
 * direction, D11) -- deliberately the minimum needed to prove D11's
 * authorization-model hard gate (WSJF #6) is real: server-derived role
 * (GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, never a client-
 * supplied field), a per-route policy check enforced here, and a denial
 * test proving a wrong-role principal gets 403, not data (see
 * CrossDomainControllerTest). Not a finished feature -- everything else
 * in D11's "Permitted cross-domain" table follows the same pattern once
 * this shape is established, per the Karpathy "don't build a full RBAC
 * framework speculatively" constraint this task was scoped under.
 *
 * Response is deliberately coarse (a count and a boolean, never a device
 * id/name/location) -- matches D11's own "minimal aggregate assertion"
 * framing, just applied to the cabin-to-family direction instead of the
 * presence-to-security direction that section already describes.
 */
@RestController
@RequestMapping("/api/cross-domain")
@CrossOrigin
public class CrossDomainController {

    private final ActiveAlertService activeAlerts;

    public CrossDomainController(ActiveAlertService activeAlerts) {
        this.activeAlerts = activeAlerts;
    }

    /**
     * Coarse cabin-alert summary for family-side scheduling/awareness use.
     * ADMINISTRATOR and ADULT_HOUSEHOLD_MEMBER only -- a CHILD-role
     * principal is denied. This is exactly the kind of household-facing
     * cross-domain read D11's hard gate exists for: real per-route policy,
     * not a blanket "any authenticated caller" check.
     */
    @GetMapping("/cabin-status-summary")
    public ResponseEntity<Map<String, Object>> cabinStatusSummary(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR && role != HouseholdRole.ADULT_HOUSEHOLD_MEMBER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "This role cannot view cabin status"));
        }
        ActiveAlertsSnapshot snapshot = activeAlerts.snapshot();
        return ResponseEntity.ok(Map.of(
            "criticalAlertCount", snapshot.counts().getOrDefault("CRITICAL", 0L),
            "warnAlertCount", snapshot.counts().getOrDefault("WARN", 0L),
            "anyActiveAlert", snapshot.counts().getOrDefault("TOTAL", 0L) > 0,
            "generatedAt", snapshot.generatedAt()));
    }
}
