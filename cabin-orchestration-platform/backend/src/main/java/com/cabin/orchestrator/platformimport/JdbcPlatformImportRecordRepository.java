package com.cabin.orchestrator.platformimport;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * WSJF #9's dedup gate: (platform, original_id) is a real database PRIMARY
 * KEY (a stronger guarantee than an application-level check -- a genuinely
 * concurrent double-sync can't slip a duplicate row past the database
 * itself), never just an application-level check. upsert() never creates a
 * second row for an already-seen (platform, originalId) pair and never
 * overwrites confirmedEntityId -- only PlatformImportController.confirm()'s
 * eventual real implementation (not this WSJF #9 item -- see its own
 * javadoc) is meant to set that.
 */
@Repository
public class JdbcPlatformImportRecordRepository implements PlatformImportRecordRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public JdbcPlatformImportRecordRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS platform_import_record (
              platform            TEXT NOT NULL,
              original_id         TEXT NOT NULL,
              original_name       TEXT NOT NULL,
              original_location   TEXT,
              raw_payload         TEXT NOT NULL,
              confirmed_entity_id TEXT,
              imported_at         TIMESTAMPTZ NOT NULL,
              updated_at          TIMESTAMPTZ NOT NULL,
              PRIMARY KEY (platform, original_id)
            )""");
    }

    @Override
    public ImportUpsertOutcome upsert(RawImportRecord raw) {
        Optional<PlatformImportRecord> existing = find(raw.platform(), raw.originalId());
        Instant now = Instant.now();
        String rawJson;
        try {
            rawJson = mapper.writeValueAsString(raw.rawPayload());
        } catch (Exception e) {
            throw new IllegalStateException(
                "Failed to serialize raw import payload for " + raw.platform() + "/" + raw.originalId(), e);
        }
        if (existing.isEmpty()) {
            jdbc.update("""
                INSERT INTO platform_import_record
                  (platform, original_id, original_name, original_location, raw_payload, confirmed_entity_id, imported_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
                """,
                raw.platform(), raw.originalId(), raw.originalName(), raw.originalLocation(), rawJson,
                now.atOffset(ZoneOffset.UTC), now.atOffset(ZoneOffset.UTC));
            return ImportUpsertOutcome.NEW;
        }
        jdbc.update("""
            UPDATE platform_import_record
            SET original_name = ?, original_location = ?, raw_payload = ?, updated_at = ?
            WHERE platform = ? AND original_id = ?
            """,
            raw.originalName(), raw.originalLocation(), rawJson, now.atOffset(ZoneOffset.UTC),
            raw.platform(), raw.originalId());
        return existing.get().confirmedEntityId() != null ? ImportUpsertOutcome.ALREADY_CONFIRMED : ImportUpsertOutcome.ALREADY_PENDING;
    }

    @Override
    public List<PlatformImportRecord> loadAll() {
        return jdbc.query("SELECT * FROM platform_import_record ORDER BY platform, original_id", (rs, rowNum) -> fromRow(rs));
    }

    @Override
    public List<PlatformImportRecord> findByPlatform(String platform) {
        return jdbc.query("SELECT * FROM platform_import_record WHERE platform = ? ORDER BY original_id",
            (rs, rowNum) -> fromRow(rs), platform);
    }

    @Override
    public Optional<PlatformImportRecord> find(String platform, String originalId) {
        return jdbc.query("SELECT * FROM platform_import_record WHERE platform = ? AND original_id = ?",
            (rs, rowNum) -> fromRow(rs), platform, originalId).stream().findFirst();
    }

    private static PlatformImportRecord fromRow(ResultSet rs) throws SQLException {
        OffsetDateTime importedAt = rs.getObject("imported_at", OffsetDateTime.class);
        OffsetDateTime updatedAt = rs.getObject("updated_at", OffsetDateTime.class);
        return new PlatformImportRecord(
            rs.getString("platform"), rs.getString("original_id"), rs.getString("original_name"),
            rs.getString("original_location"), rs.getString("raw_payload"), rs.getString("confirmed_entity_id"),
            importedAt == null ? null : importedAt.toInstant(),
            updatedAt == null ? null : updatedAt.toInstant());
    }
}
