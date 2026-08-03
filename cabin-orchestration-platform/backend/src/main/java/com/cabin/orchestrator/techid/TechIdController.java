package com.cabin.orchestrator.techid;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Provider-agnostic findings intake. Two auth tiers on purpose:
 *
 *  - POST (submit) is gated by a shared-secret API key (X-Tech-Id-Api-Key),
 *    not GoogleAuthInterceptor -- submitters are automated services (the
 *    reference Claude Code routine, an operator's paid scanning tier, or
 *    an instance owner's own AI of choice), not signed-in humans. Any
 *    caller holding the configured key can submit as any `provider` name
 *    it claims -- the key controls *whether* a caller may submit, not
 *    *which* provider identity it may claim. That's deliberate: providers
 *    are free text (see TechIdFinding's javadoc), so there's no registry
 *    of provider identities to check a claim against.
 *  - GET (read) is open, matching the /api/events and /api/devices
 *    precedent -- findings aren't more sensitive than event/device data.
 *  - PATCH (adjudicate: reviewed/actioned/dismissed) requires a human,
 *    so it rides GoogleAuthInterceptor via WebConfig's /api/tech-id/**
 *    pattern -- this is the "adjudication and decision making" step the
 *    findings feed into, done by a person, not a machine caller.
 */
@RestController
@RequestMapping("/api/tech-id/findings")
@CrossOrigin
public class TechIdController {

    private final TechIdFindingService findingService;

    @Value("${cabin.techid.apiKey:}")
    private String apiKey;

    public TechIdController(TechIdFindingService findingService) {
        this.findingService = findingService;
    }

    public record SubmitRequest(
        String entityId,
        String provider,
        String findingType,
        String summary,
        String confidence,
        List<String> sources,
        Long checkedAt
    ) {}

    public record StatusUpdateRequest(String status) {}

    @PostMapping
    public ResponseEntity<?> submit(@RequestBody SubmitRequest body, HttpServletRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Tech ID Service submission is not configured on this instance (cabin.techid.apiKey unset)."));
        }
        String presented = request.getHeader("X-Tech-Id-Api-Key");
        if (presented == null || !apiKey.equals(presented)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Missing or invalid X-Tech-Id-Api-Key."));
        }
        if (body.entityId() == null || body.provider() == null || body.findingType() == null
                || body.summary() == null || body.confidence() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                "entityId, provider, findingType, summary, and confidence are required."));
        }
        long checkedAt = body.checkedAt() != null ? body.checkedAt() : System.currentTimeMillis();
        List<String> sources = body.sources() != null ? body.sources() : List.of();
        TechIdFinding finding = findingService.submit(
            body.entityId(), body.provider(), body.findingType(), body.summary(),
            body.confidence(), sources, checkedAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(finding);
    }

    /**
     * GET /api/tech-id/findings?entityId=nvr_frigate&limit=20
     */
    @GetMapping
    public List<TechIdFinding> recent(
            @RequestParam(name = "entityId", required = false) String entityId,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit) {
        int cappedLimit = Math.min(Math.max(limit, 1), 200);
        return findingService.recent(entityId, cappedLimit);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> adjudicate(@PathVariable String id, @RequestBody StatusUpdateRequest body) {
        if (body.status() == null || !List.of("new", "reviewed", "actioned", "dismissed").contains(body.status())) {
            return ResponseEntity.badRequest().body(Map.of("error", "status must be one of: new, reviewed, actioned, dismissed"));
        }
        TechIdFinding updated = findingService.updateStatus(id, body.status());
        if (updated == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(updated);
    }
}
