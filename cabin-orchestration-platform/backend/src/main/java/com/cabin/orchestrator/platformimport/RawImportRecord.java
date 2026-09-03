package com.cabin.orchestrator.platformimport;

import java.util.List;
import java.util.Map;

/**
 * What one PlatformImportProvider.listDevices() call returns for a single
 * external device -- the raw, unconfirmed shape D10 (docs/ontology/DECISIONS.md)
 * describes: originalName/originalLocation/platform/originalId preserved
 * verbatim, never overwritten once persisted (see
 * JdbcPlatformImportRecordRepository.upsert()). measurementTypeCandidates is
 * the platform-specific capability/kind mapping already applied by the
 * adapter that produced this record -- each platform's own vocabulary is
 * different, so only that adapter knows how to read its raw payload;
 * PlatformImportTranslationService consumes this list as-is, it never
 * re-derives it.
 */
public record RawImportRecord(
    String platform,
    String originalId,
    String originalName,
    String originalLocation,
    List<String> measurementTypeCandidates,
    Map<String, Object> rawPayload
) {}
