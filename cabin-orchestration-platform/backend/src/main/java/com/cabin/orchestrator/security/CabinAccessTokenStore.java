package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CabinAccessTokenStore {
    List<CabinAccessToken> loadAll();

    Optional<CabinAccessToken> findByToken(String token);

    void save(CabinAccessToken token);

    /** Soft delete -- a revoked link's history stays queryable, matching this codebase's never-hard-delete-admin-data discipline. */
    void revoke(String id, Instant revokedAt);
}
