package com.cabin.orchestrator.platformimport;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WSJF #9 success criterion 1, against a real Postgres -- not a fake -- so
 * the UNIQUE constraint claim is actually verified, not just assumed from
 * the application-level upsert logic alone.
 */
@Testcontainers
class JdbcPlatformImportRecordRepositoryTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcPlatformImportRecordRepository newRepository() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcPlatformImportRecordRepository(jdbc);
    }

    private static RawImportRecord raw(String originalId, String name, String location) {
        return new RawImportRecord("smartthings", originalId, name, location, List.of("temperature"), Map.of("k", "v"));
    }

    @Test
    void firstImportOfADeviceIsNew() {
        JdbcPlatformImportRecordRepository repo = newRepository();

        ImportUpsertOutcome outcome = repo.upsert(raw("dev-" + System.nanoTime(), "Sensor", "loc-1"));

        assertEquals(ImportUpsertOutcome.NEW, outcome);
    }

    @Test
    void reimportOfTheSameDeviceBeforeConfirmationIsAlreadyPendingNotADuplicateRow() {
        JdbcPlatformImportRecordRepository repo = newRepository();
        String id = "dev-" + System.nanoTime();
        repo.upsert(raw(id, "Sensor", "loc-1"));

        ImportUpsertOutcome outcome = repo.upsert(raw(id, "Sensor (renamed)", "loc-1"));

        assertEquals(ImportUpsertOutcome.ALREADY_PENDING, outcome);
        assertEquals(1, repo.findByPlatform("smartthings").stream().filter(r -> r.originalId().equals(id)).count(),
            "must never create a second row for the same (platform, originalId)");
        assertEquals("Sensor (renamed)", repo.find("smartthings", id).orElseThrow().originalName(),
            "raw fields do refresh on a re-import");
    }

    @Test
    void reimportOfAnAlreadyConfirmedDeviceRefreshesMetadataWithoutTouchingTheConfirmedEntityId() {
        JdbcPlatformImportRecordRepository repo = newRepository();
        String id = "dev-" + System.nanoTime();
        repo.upsert(raw(id, "Sensor", "loc-1"));
        markConfirmed(repo, id, "smartthings-sensor");

        ImportUpsertOutcome outcome = repo.upsert(raw(id, "Sensor v2", "loc-2"));

        assertEquals(ImportUpsertOutcome.ALREADY_CONFIRMED, outcome);
        PlatformImportRecord after = repo.find("smartthings", id).orElseThrow();
        assertEquals("smartthings-sensor", after.confirmedEntityId(), "a re-import must never clear or alter a confirmed entity id");
        assertEquals("Sensor v2", after.originalName());
    }

    @Test
    void theUniqueConstraintIsRealAtTheDatabaseLevelNotJustAnApplicationCheck() {
        JdbcPlatformImportRecordRepository repo = newRepository();
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        String id = "dev-" + System.nanoTime();
        repo.upsert(raw(id, "Sensor", "loc-1"));

        assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
            INSERT INTO platform_import_record
              (platform, original_id, original_name, original_location, raw_payload, confirmed_entity_id, imported_at, updated_at)
            VALUES (?, ?, ?, ?, ?, NULL, ?, ?)
            """,
            "smartthings", id, "Duplicate attempt", "loc-1", "{}",
            OffsetDateTime.now(), OffsetDateTime.now()),
            "a raw INSERT bypassing upsert()'s own ON CONFLICT logic must still be rejected by the schema itself");
    }

    @Test
    void differentPlatformsWithTheSameOriginalIdAreNotTreatedAsTheSameDevice() {
        JdbcPlatformImportRecordRepository repo = newRepository();
        String sharedId = "shared-" + System.nanoTime();
        repo.upsert(raw(sharedId, "SmartThings Sensor", "loc-1"));

        ImportUpsertOutcome outcome = repo.upsert(new RawImportRecord("ring", sharedId, "Ring Sensor", "addr", List.of("motion"), Map.of()));

        assertEquals(ImportUpsertOutcome.NEW, outcome, "the dedup key is (platform, originalId), not originalId alone");
    }

    /** Directly sets confirmedEntityId to simulate a future real confirm() implementation -- this test class predates that work. */
    private static void markConfirmed(JdbcPlatformImportRecordRepository repo, String originalId, String entityId) {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.update("UPDATE platform_import_record SET confirmed_entity_id = ? WHERE platform = ? AND original_id = ?",
            entityId, "smartthings", originalId);
    }
}
