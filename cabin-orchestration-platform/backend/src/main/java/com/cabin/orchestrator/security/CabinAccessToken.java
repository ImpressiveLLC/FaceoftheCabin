package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.List;

/**
 * A Tier 1 guest share link -- see the plan's "Guest Access Model" section
 * and CabinAccessTokenService's own doc. Admin-issued, always read-only,
 * scope-limited to a fixed set of path prefixes (see GoogleAuthInterceptor's
 * SCOPE_PATH_PREFIXES). {@code token} is the actual bearer secret handed out
 * in the link; {@code id} is a separate, stable identifier used for
 * listing/revoking in the admin UI so the secret itself never needs to
 * round-trip back to the browser after creation.
 */
public record CabinAccessToken(
    String id,
    String token,
    String label,
    List<String> scope,
    Instant expiresAt,   // nullable -- no expiry
    Instant revokedAt,   // nullable -- still active
    String createdBy,
    Instant createdAt
) {
    public boolean isValid(Instant now) {
        if (revokedAt != null) return false;
        return expiresAt == null || !now.isAfter(expiresAt);
    }
}
