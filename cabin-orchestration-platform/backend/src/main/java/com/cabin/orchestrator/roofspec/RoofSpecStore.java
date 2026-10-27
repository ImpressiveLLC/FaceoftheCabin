package com.cabin.orchestrator.roofspec;

import java.time.Instant;
import java.util.List;

public interface RoofSpecStore {
    /** Live (not deleted) entries, oldest first. */
    List<RoofSpecEntry> loadLive();

    long countLiveByGuestToken(String guestTokenId);

    void save(RoofSpecEntry entry);

    /** Soft delete, matching this codebase's never-hard-delete discipline. */
    void softDelete(String id, Instant at);
}
