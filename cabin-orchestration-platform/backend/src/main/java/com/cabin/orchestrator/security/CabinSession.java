package com.cabin.orchestrator.security;

import java.time.Instant;

/**
 * 2026-09-04 -- the "known user, signed in, recognized for a rolling 30
 * days" credential a real Google-authenticated caller's browser holds
 * after exchanging a fresh Google access token via POST /api/auth/session
 * (AuthController). Google's own access token is short-lived (~1 hour) and
 * requires an interactive popup to renew, which is exactly the walk-up
 * friction this app's kiosk/glanceable use case can't tolerate. This session
 * exists precisely to decouple "has this person proven who they are
 * recently" from "does the caller currently hold a live Google token."
 *
 * Deliberately a SLIDING window, unlike ManagedUserSession's fixed 90-day
 * expiry-from-creation: googleEmail is re-derived and expiresAt is pushed
 * forward another 30 days on every successful validate (see
 * CabinSessionService.validateAndExtend) -- "signed in within the last 30
 * days, from either the cabin app or Family Hub, stays recognized" is a
 * rolling-recency test, not a fixed-lifetime one.
 */
public record CabinSession(
    String token,
    String googleEmail,
    Instant expiresAt,
    Instant revokedAt, // nullable -- still active
    Instant createdAt
) {
    public boolean isValid(Instant now) {
        if (revokedAt != null) return false;
        return !now.isAfter(expiresAt);
    }
}
