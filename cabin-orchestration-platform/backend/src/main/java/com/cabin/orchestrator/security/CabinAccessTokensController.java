package com.cabin.orchestrator.security;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin-only management of Tier 1 guest share links -- gated by
 * GoogleAuthInterceptor same as every other admin surface (see
 * WebConfig's path list). A guest token itself is never accepted here:
 * creating/listing/revoking links is exactly the kind of action a share
 * link must never be able to do to itself.
 */
@RestController
@RequestMapping("/api/access-tokens")
@CrossOrigin
public class CabinAccessTokensController {

    private final CabinAccessTokenService tokens;

    public CabinAccessTokensController(CabinAccessTokenService tokens) {
        this.tokens = tokens;
    }

    /** A link plus how it has been used. The token fields stay flat so existing readers of this list are unaffected. */
    public record AccessLinkView(@JsonUnwrapped CabinAccessToken token, Instant firstUsedAt, Instant lastUsedAt, long useCount) { }

    @GetMapping
    public List<AccessLinkView> list() {
        Map<String, AccessUsage> usage = tokens.usage();
        return tokens.list().stream().map(t -> {
            AccessUsage u = usage.getOrDefault(t.id(), AccessUsage.NONE);
            return new AccessLinkView(t, u.firstUsedAt(), u.lastUsedAt(), u.useCount());
        }).toList();
    }

    /** Who opened a link and what they looked at, newest first. Admin only (the whole prefix is gated and denied to demo links). */
    @GetMapping("/{id}/activity")
    public List<AccessEvent> activity(@PathVariable String id, @RequestParam(defaultValue = "30") int days) {
        return tokens.activity(id, days);
    }

    @PostMapping
    public CabinAccessToken create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        String label = (String) body.get("label");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("label is required");
        @SuppressWarnings("unchecked")
        List<String> scope = (List<String>) body.getOrDefault("scope", List.of());
        if (scope.isEmpty()) throw new IllegalArgumentException("scope must include at least one of dashboard, device_states, alerts_read");
        Object expiresInDays = body.get("expiresInDays");
        Duration ttl = expiresInDays == null ? null : Duration.ofDays(((Number) expiresInDays).longValue());
        String createdBy = (String) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        return tokens.create(label, scope, ttl, createdBy);
    }

    @DeleteMapping("/{id}")
    public void revoke(@PathVariable String id) {
        tokens.revoke(id);
    }
}
