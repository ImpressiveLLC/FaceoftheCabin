package com.cabin.orchestrator.security;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Tier 1 guest access -- see the plan's "Guest Access Model" section. Built
 * for the concrete case of an insurance adjuster and their remediation team
 * (no Google account) needing read-only current-conditions visibility for
 * an active claim, without Nate creating or sharing any real credential.
 *
 * A link's bearer secret ({@code token}) is never re-derivable from its
 * {@code id} -- the admin UI lists/revokes by id, and only shows the full
 * link once, at creation time, matching how a real invite link should
 * behave (nothing to leak from a later "list my links" view).
 */
@Service
public class CabinAccessTokenService {

    private final CabinAccessTokenStore store;

    public CabinAccessTokenService(CabinAccessTokenStore store) {
        this.store = store;
    }

    public CabinAccessToken create(String label, List<String> scope, Duration ttl, String createdBy) {
        // D22 R-DM-1: a demo token holds "demo" and nothing else, and always expires.
        if (scope != null && scope.contains("demo")) {
            if (scope.size() != 1) throw new IllegalArgumentException("a demo link holds the demo scope only");
            if (ttl == null) ttl = Duration.ofDays(30);
        }
        Instant now = Instant.now();
        CabinAccessToken token = new CabinAccessToken(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            label, scope,
            ttl == null ? null : now.plus(ttl),
            null, createdBy, now);
        store.save(token);
        return token;
    }

    public List<CabinAccessToken> list() {
        return store.loadAll();
    }

    public void revoke(String id) {
        store.revoke(id, Instant.now());
    }

    /** The link a raw bearer token maps to, whether or not it is still active -- lets DemoAccessFilter tell an expired demo link (401 GUEST_LINK_INACTIVE) from a non-demo token it should leave alone. */
    public Optional<CabinAccessToken> lookup(String rawToken) {
        return store.findByToken(rawToken);
    }

    /** Empty unless the raw bearer token maps to a link that's genuinely active right now (not expired, not revoked). */
    public Optional<CabinAccessToken> validate(String rawToken) {
        return store.findByToken(rawToken).filter(t -> t.isValid(Instant.now()));
    }
}
