package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
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

    @GetMapping
    public List<CabinAccessToken> list() {
        return tokens.list();
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
