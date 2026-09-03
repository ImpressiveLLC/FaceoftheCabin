package com.cabin.orchestrator.platformimport;

import java.time.Instant;

/**
 * The persisted row -- (platform, originalId) is the real database UNIQUE
 * constraint (see JdbcPlatformImportRecordRepository). confirmedEntityId is
 * null until a person confirms this import into a real device; nothing in
 * this WSJF #9 item ever sets it (PlatformImportController.confirm() is a
 * deliberate stub -- see its own javadoc), so every record stays
 * ALREADY_PENDING until that follow-up work lands.
 */
public record PlatformImportRecord(
    String platform,
    String originalId,
    String originalName,
    String originalLocation,
    String rawPayloadJson,
    String confirmedEntityId,
    Instant importedAt,
    Instant updatedAt
) {}
