package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface CabinAccessTokenStore {
    List<CabinAccessToken> loadAll();

    Optional<CabinAccessToken> findByToken(String token);

    void save(CabinAccessToken token);

    /** Soft delete -- a revoked link's history stays queryable, matching this codebase's never-hard-delete-admin-data discipline. */
    void revoke(String id, Instant revokedAt);

    // Usage tracking. Defaults keep stores that do not track usage (test fakes) working unchanged.

    /** Records one use: upserts the event into its 5-minute bucket and updates the link's summary. */
    default void recordUse(String tokenId, Instant at, String tier, String route, String policyClass,
                           int status, String clientIp, String userAgent, boolean pageOpen) { }

    /** Events for one link since {@code since}, newest first, at most {@code limit}. */
    default List<AccessEvent> events(String tokenId, Instant since, int limit) { return List.of(); }

    /** Usage summary per link id; links never used are absent. */
    default Map<String, AccessUsage> usage() { return Map.of(); }

    /** Deletes events older than {@code cutoff}; returns how many rows went. */
    default int purgeEventsBefore(Instant cutoff) { return 0; }
}
