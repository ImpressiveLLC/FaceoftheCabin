package com.cabin.orchestrator.security;

import java.time.Instant;

/**
 * The real, ongoing credential a managed user's browser holds after
 * clicking their magic link -- genuinely their own, not a proxy of the
 * admin's session (see ManagedUser's own doc). Long-lived by design
 * (unlike MagicLinkToken); revoked explicitly rather than expiring quickly,
 * matching how a Google session's own access token is re-derived on demand
 * rather than needing the person to re-authenticate constantly.
 */
public record ManagedUserSession(
    String token,
    String managedUserId,
    Instant expiresAt,
    Instant revokedAt, // nullable -- still active
    Instant createdAt
) {
    public boolean isValid(Instant now) {
        if (revokedAt != null) return false;
        return !now.isAfter(expiresAt);
    }
}
