package com.cabin.orchestrator.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres (Testcontainers), real gzip files on a real temp
 * directory -- see TelemetryArchivalService's own javadoc for why this
 * exists (tiered retention for sensor-reading TELEMETRY events, requested
 * 2026-08-25, real data volume checked live before designing it: 33MB for
 * 36k rows / 19 days of production traffic).
 */
@Testcontainers
class TelemetryArchivalServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // @Container reuses one Postgres instance across every test method in
    // this class (not a fresh one per test) -- found live by two of this
    // file's own tests failing on stale rows from an earlier method before
    // this truncate existed. Each test starts from a genuinely clean table.
    private JdbcTemplate jdbc() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cabin_event (
              event_id   TEXT PRIMARY KEY,
              time       TIMESTAMPTZ NOT NULL DEFAULT now(),
              device_id  TEXT,
              event_type TEXT NOT NULL,
              severity   TEXT NOT NULL,
              payload    JSONB
            )""");
        jdbc.execute("TRUNCATE TABLE cabin_event");
        return jdbc;
    }

    private void insertEvent(JdbcTemplate jdbc, String id, String time, String deviceId, String eventType, String payloadJson) {
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            VALUES (?, ?::timestamptz, ?, ?, 'INFO', ?::jsonb)
            """, id, time, deviceId, eventType, payloadJson);
    }

    @Test
    void archivesAWholeMonthOfTelemetryToAGzippedJsonlFileAndDeletesItFromTheLiveTable(@TempDir Path tempDir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        insertEvent(jdbc, "e1", "2026-05-10T08:00:00Z", "z2m-leak_mech_room", "TELEMETRY", "{\"water_leak\": false}");
        insertEvent(jdbc, "e2", "2026-05-20T08:00:00Z", "z2m-temp_kitchen", "TELEMETRY", "{\"temperature\": 68.2}");
        // A different event type in the same month must not be touched.
        insertEvent(jdbc, "e3", "2026-05-15T08:00:00Z", "front_door", "DETECTION_NEW", "{\"label\": \"person\"}");

        TelemetryArchivalService service = new TelemetryArchivalService(jdbc);
        ReflectionTestUtils.setField(service, "archiveDir", tempDir.toString());
        service.archiveMonth(YearMonth.of(2026, 5));

        Path archiveFile = tempDir.resolve("2026-05.jsonl.gz");
        assertTrue(Files.exists(archiveFile), "expected an archive file to be written");
        List<String> lines = readGzippedLines(archiveFile);
        assertEquals(2, lines.size(), "only the 2 TELEMETRY rows, not the DETECTION_NEW row");
        assertTrue(lines.stream().anyMatch(l -> l.contains("z2m-leak_mech_room")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("z2m-temp_kitchen")));

        Integer remainingTelemetry = jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_type = 'TELEMETRY'", Integer.class);
        assertEquals(0, remainingTelemetry, "archived TELEMETRY rows must be deleted from the live table");
        Integer remainingDetection = jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_type = 'DETECTION_NEW'", Integer.class);
        assertEquals(1, remainingDetection, "non-TELEMETRY rows are never touched by this service");
    }

    @Test
    void aMonthWithNoTelemetryRowsWritesNoFileAndDoesNotThrow(@TempDir Path tempDir) {
        JdbcTemplate jdbc = jdbc();
        TelemetryArchivalService service = new TelemetryArchivalService(jdbc);
        ReflectionTestUtils.setField(service, "archiveDir", tempDir.toString());

        assertDoesNotThrow(() -> service.archiveMonth(YearMonth.of(2026, 1)));
        assertFalse(Files.exists(tempDir.resolve("2026-01.jsonl.gz")));
    }

    @Test
    void aMonthAlreadyArchivedIsSkippedEntirelyOnASecondRun(@TempDir Path tempDir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        insertEvent(jdbc, "e1", "2026-05-10T08:00:00Z", "z2m-leak_mech_room", "TELEMETRY", "{\"water_leak\": false}");
        TelemetryArchivalService service = new TelemetryArchivalService(jdbc);
        ReflectionTestUtils.setField(service, "archiveDir", tempDir.toString());
        service.archiveMonth(YearMonth.of(2026, 5));

        // Re-insert a row for the same month (simulating a hand-restored
        // row, or just proving the skip is by file presence, not an empty
        // table) and run again -- must not touch it or rewrite the file.
        insertEvent(jdbc, "e2", "2026-05-11T08:00:00Z", "z2m-leak_mech_room", "TELEMETRY", "{\"water_leak\": true}");
        long beforeMtime = Files.getLastModifiedTime(tempDir.resolve("2026-05.jsonl.gz")).toMillis();
        Thread.sleep(5);
        service.archiveMonth(YearMonth.of(2026, 5));

        long afterMtime = Files.getLastModifiedTime(tempDir.resolve("2026-05.jsonl.gz")).toMillis();
        assertEquals(beforeMtime, afterMtime, "an already-archived month's file must not be rewritten");
        Integer stillLive = jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_type = 'TELEMETRY'", Integer.class);
        assertEquals(1, stillLive, "the row inserted after archiving must be left alone, not silently deleted");
    }

    @Test
    void archiveOldMonthsSkipsWhenDisabled(@TempDir Path tempDir) {
        JdbcTemplate jdbc = jdbc();
        insertEvent(jdbc, "e1", "2020-01-10T08:00:00Z", "z2m-leak_mech_room", "TELEMETRY", "{\"water_leak\": false}");
        TelemetryArchivalService service = new TelemetryArchivalService(jdbc);
        ReflectionTestUtils.setField(service, "archiveDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "enabled", false);
        ReflectionTestUtils.setField(service, "hotRetentionMonths", 3);

        service.archiveOldMonths();

        Integer stillLive = jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_type = 'TELEMETRY'", Integer.class);
        assertEquals(1, stillLive, "disabled means no archiving at all, regardless of how old the data is");
    }

    @Test
    void archiveOldMonthsCatchesUpEveryEligibleMonthNotJustTheLatest(@TempDir Path tempDir) {
        JdbcTemplate jdbc = jdbc();
        insertEvent(jdbc, "e1", "2020-01-10T08:00:00Z", "z2m-leak_mech_room", "TELEMETRY", "{\"water_leak\": false}");
        insertEvent(jdbc, "e2", "2020-02-10T08:00:00Z", "z2m-leak_mech_room", "TELEMETRY", "{\"water_leak\": false}");
        TelemetryArchivalService service = new TelemetryArchivalService(jdbc);
        ReflectionTestUtils.setField(service, "archiveDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "hotRetentionMonths", 3);

        service.archiveOldMonths();

        assertTrue(Files.exists(tempDir.resolve("2020-01.jsonl.gz")));
        assertTrue(Files.exists(tempDir.resolve("2020-02.jsonl.gz")));
        Integer stillLive = jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_type = 'TELEMETRY'", Integer.class);
        assertEquals(0, stillLive);
    }

    @Test
    void archiveOldMonthsLeavesTheCurrentHotWindowAlone(@TempDir Path tempDir) {
        JdbcTemplate jdbc = jdbc();
        // "Now" for this test is whenever it actually runs -- insert a row
        // for the current month, which must never be archived regardless
        // of when this test executes.
        String thisMonthTimestamp = YearMonth.now(java.time.ZoneOffset.UTC).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString();
        insertEvent(jdbc, "e1", thisMonthTimestamp, "z2m-temp_kitchen", "TELEMETRY", "{\"temperature\": 70}");
        TelemetryArchivalService service = new TelemetryArchivalService(jdbc);
        ReflectionTestUtils.setField(service, "archiveDir", tempDir.toString());
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "hotRetentionMonths", 3);

        service.archiveOldMonths();

        Integer stillLive = jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_type = 'TELEMETRY'", Integer.class);
        assertEquals(1, stillLive, "the current month must stay in the hot window");
    }

    private List<String> readGzippedLines(Path file) throws Exception {
        try (var in = Files.newInputStream(file); var gzip = new GZIPInputStream(in)) {
            return new String(gzip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
        }
    }
}
