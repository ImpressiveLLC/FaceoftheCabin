package com.cabin.orchestrator.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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

    private static final Logger log = LoggerFactory.getLogger(CabinAccessTokenService.class);

    private final CabinAccessTokenStore store;

    @Value("${cabin.access.events-retention-days:90}")
    private int eventsRetentionDays = 90;

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

    /**
     * Records one use of a link. Never throws: a failure to write the trail must not turn a
     * working request into an error. The bearer token is not an input, only the link's id, and a
     * route that happens to contain the token is masked before it is stored.
     */
    public void recordUse(CabinAccessToken token, String tier, String route, String policyClass,
                          int status, String clientIp, String userAgent, boolean pageOpen) {
        try {
            String safeRoute = route == null ? "" : route.replace(token.token(), "[token]");
            store.recordUse(token.id(), Instant.now(), tier, safeRoute, policyClass, status, clientIp, userAgent, pageOpen);
        } catch (RuntimeException e) {
            log.warn("access event not recorded for link {}: {}", token.id(), e.getMessage());
        }
    }

    public List<AccessEvent> activity(String tokenId, int days) {
        int clamped = Math.max(1, Math.min(days, Math.max(1, eventsRetentionDays)));
        return store.events(tokenId, Instant.now().minus(Duration.ofDays(clamped)), 500);
    }

    public Map<String, AccessUsage> usage() {
        return store.usage();
    }

    /** Daily purge of the usage trail (cabin.access.events-retention-days, default 90). */
    @Scheduled(cron = "${cabin.access.events-purge-cron:0 30 3 * * *}")
    public void purgeOldEvents() {
        int removed = store.purgeEventsBefore(Instant.now().minus(Duration.ofDays(Math.max(1, eventsRetentionDays))));
        if (removed > 0) log.info("access events: purged {} rows older than {} days", removed, eventsRetentionDays);
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
