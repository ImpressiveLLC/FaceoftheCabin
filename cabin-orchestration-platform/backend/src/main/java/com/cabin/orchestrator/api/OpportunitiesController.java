package com.cabin.orchestrator.api;

import com.cabin.orchestrator.opportunities.JdbcOptimizationOpportunityStore;
import com.cabin.orchestrator.opportunities.OpportunityStatus;
import com.cabin.orchestrator.opportunities.OptimizationOpportunity;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sprint 5 WSJF #1 (r5 handover). ADMINISTRATOR-only, same per-route
 * pattern PlatformImportController/SystemController already established:
 * read HouseholdRole from the request attribute GoogleAuthInterceptor
 * already set, never re-derive it. Gated this tightly (not
 * ADULT_HOUSEHOLD_MEMBER-and-above, unlike platform-import's read-only
 * /records) because a wrong anomaly reading tied to a safety-relevant
 * device (see OptimizationAnalyticsService's own javadoc) shouldn't be
 * blasted to every signed-in household member by default -- an operator
 * surface, not a glanceable one.
 */
@RestController
@RequestMapping("/api/opportunities")
@CrossOrigin
public class OpportunitiesController {

    private final JdbcOptimizationOpportunityStore store;

    public OpportunitiesController(JdbcOptimizationOpportunityStore store) {
        this.store = store;
    }

    /** GET /api/opportunities?status=open&limit=50&offset=0 -- status is optional (omit for every status). */
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "50") int limit,
                                   @RequestParam(defaultValue = "0") int offset,
                                   HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;

        OpportunityStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = OpportunityStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown status: " + status));
            }
        }
        List<OptimizationOpportunity> all = store.findAll(filter);
        int from = Math.min(Math.max(offset, 0), all.size());
        int to = Math.min(from + Math.max(limit, 0), all.size());
        return ResponseEntity.ok(all.subList(from, to));
    }

    /** PATCH /api/opportunities/{id}/status -- body {"status": "ACKNOWLEDGED"|"RESOLVED"}. OPEN is never a valid target here (see OpportunityStatus's own doc). */
    @PatchMapping("/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable String id, @RequestBody Map<String, String> body,
                                           HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;

        OpportunityStatus newStatus;
        try {
            newStatus = OpportunityStatus.valueOf(String.valueOf(body.get("status")).toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "status must be ACKNOWLEDGED or RESOLVED"));
        }
        if (newStatus == OpportunityStatus.OPEN) {
            return ResponseEntity.badRequest().body(Map.of("error",
                "Cannot set status back to OPEN directly -- a resolved condition that recurs opens as a new entry automatically"));
        }

        Optional<OptimizationOpportunity> existingOpt = store.findById(id);
        if (existingOpt.isEmpty()) return ResponseEntity.notFound().build();
        OptimizationOpportunity existing = existingOpt.get();

        OptimizationOpportunity updated = new OptimizationOpportunity(
            existing.id(), existing.opportunityType(), existing.deviceId(), existing.detectedAt(),
            existing.evidence(), newStatus, newStatus == OpportunityStatus.RESOLVED ? Instant.now() : null);
        store.save(updated);
        return ResponseEntity.ok(updated);
    }

    private ResponseEntity<?> requireAdministrator(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This role cannot view or manage optimization opportunities"));
        }
        return null;
    }
}
