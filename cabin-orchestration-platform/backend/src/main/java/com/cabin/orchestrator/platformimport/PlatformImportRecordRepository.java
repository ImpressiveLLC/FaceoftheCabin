package com.cabin.orchestrator.platformimport;

import java.util.List;
import java.util.Optional;

/** Persists platform import records under the (platform, originalId) dedup gate. See ImportUpsertOutcome for what upsert() returns and why. */
public interface PlatformImportRecordRepository {
    ImportUpsertOutcome upsert(RawImportRecord raw);
    List<PlatformImportRecord> loadAll();
    List<PlatformImportRecord> findByPlatform(String platform);
    Optional<PlatformImportRecord> find(String platform, String originalId);

    /**
     * Sprint 5 WSJF #3: the write PlatformImportController.confirm() needed
     * but this interface never had -- confirmed_entity_id has existed on
     * the row since WSJF #9, but nothing ever set it (confirm() was a
     * deliberate stub). Only succeeds once per (platform, originalId): a
     * row that already has a confirmedEntityId is left untouched, matching
     * D10's "never overwrite confirmed entity_ids on re-import." Returns
     * false when no matching pending row exists, or it was already
     * confirmed -- the caller distinguishes those two cases itself by
     * re-reading find() first, this method only reports whether ITS write
     * happened.
     */
    boolean markConfirmed(String platform, String originalId, String confirmedEntityId);
}
