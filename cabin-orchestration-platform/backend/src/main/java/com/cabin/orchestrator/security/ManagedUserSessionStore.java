package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.Optional;

public interface ManagedUserSessionStore {
    void save(ManagedUserSession session);
    Optional<ManagedUserSession> findByToken(String token);
    void revoke(String token, Instant revokedAt);
}
