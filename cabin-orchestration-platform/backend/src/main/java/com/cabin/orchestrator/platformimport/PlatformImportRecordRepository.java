package com.cabin.orchestrator.platformimport;

import java.util.List;
import java.util.Optional;

/** Persists platform import records under the (platform, originalId) dedup gate. See ImportUpsertOutcome for what upsert() returns and why. */
public interface PlatformImportRecordRepository {
    ImportUpsertOutcome upsert(RawImportRecord raw);
    List<PlatformImportRecord> loadAll();
    List<PlatformImportRecord> findByPlatform(String platform);
    Optional<PlatformImportRecord> find(String platform, String originalId);
}
