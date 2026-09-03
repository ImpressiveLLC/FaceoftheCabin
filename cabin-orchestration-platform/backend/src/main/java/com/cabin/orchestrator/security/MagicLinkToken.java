package com.cabin.orchestrator.security;

import java.time.Instant;

/**
 * A single email-delivered, single-use credential, exchanged exactly once
 * for a ManagedUserSession -- never itself a standing session. Short-lived
 * on purpose (see ManagedUserService's own expiry constant): it only needs
 * to survive the gap between "email arrives" and "person clicks it."
 */
public record MagicLinkToken(
    String token,
    String managedUserId,
    Instant expiresAt,
    Instant consumedAt, // nullable -- null until exchanged for a session
    Instant createdAt
) {
    public boolean isValid(Instant now) {
        return consumedAt == null && !now.isAfter(expiresAt);
    }
}
