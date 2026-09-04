package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.Optional;

public interface CabinSessionStore {
    void save(CabinSession session);
    Optional<CabinSession> findByToken(String token);
    /** The one operation ManagedUserSessionStore doesn't need -- this session's whole point is sliding, not fixed, expiry. */
    void extend(String token, Instant newExpiresAt);
    void revoke(String token, Instant revokedAt);
}
