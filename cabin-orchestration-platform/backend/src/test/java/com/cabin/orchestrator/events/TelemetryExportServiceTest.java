package com.cabin.orchestrator.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres (Testcontainers) and real gzip and plain files in a temp directory, the same three sources the
 * export reads in production: the live cabin_event table, monthly YYYY-MM.jsonl.gz archives, and the hourly
 * incremental/incremental-YYYY-MM-DD.jsonl copy. Test data is synthetic (ids like "hot-1"), never real readings.
 */
@Testcontainers
class TelemetryExportServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    private TelemetryExportService service(JdbcTemplate jdbc, Path archiveDir) {
        TelemetryExportService service = new TelemetryExportService(jdbc);
        ReflectionTestUtils.setField(service, "archiveDir", archiveDir.toString());
        return service;
    }

    private void insert(JdbcTemplate jdbc, String id, String time, String deviceId, String eventType, String payloadJson) {
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            VALUES (?, ?::timestamptz, ?, ?, 'INFO', ?::jsonb)
            """, id, time, deviceId, eventType, payloadJson);
    }

    private static String jsonLine(String id, String time, String deviceId, String payloadJson) {
        return "{\"eventId\":\"" + id + "\",\"time\":\"" + time + "\",\"deviceId\":\"" + deviceId
            + "\",\"eventType\":\"TELEMETRY\",\"severity\":\"INFO\",\"payload\":" + payloadJson + "}";
    }

    private static void writeMonthArchive(Path dir, String month, String... lines) throws IOException {
        Files.createDirectories(dir);
        try (var gz = new GZIPOutputStream(Files.newOutputStream(dir.resolve(month + ".jsonl.gz")));
             Writer w = new OutputStreamWriter(gz, StandardCharsets.UTF_8)) {
            for (String line : lines) w.write(line + "\n");
        }
    }

    private static void writeIncremental(Path dir, String day, String... lines) throws IOException {
        Path inc = dir.resolve("incremental");
        Files.createDirectories(inc);
        Files.writeString(inc.resolve("incremental-" + day + ".jsonl"), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    private List<JsonNode> exportJson(TelemetryExportService service, TelemetryExportService.Query query) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.export(query, out);
        byte[] bytes = out.toByteArray();
        String text = query.format().id().endsWith(".gz")
            ? new String(new GZIPInputStream(new java.io.ByteArrayInputStream(bytes)).readAllBytes(), StandardCharsets.UTF_8)
            : new String(bytes, StandardCharsets.UTF_8);
        List<JsonNode> rows = new ArrayList<>();
        for (String line : text.split("\n")) if (!line.isBlank()) rows.add(MAPPER.readTree(line));
        return rows;
    }

    private static TelemetryExportService.Query all(TelemetryExportService.Format format) {
        return new TelemetryExportService.Query(null, null, Set.of(), Set.of(), format);
    }

    private static List<String> ids(List<JsonNode> rows) {
        return rows.stream().map(r -> r.get("eventId").asText()).toList();
    }

    @Test
    void hotOnlyExportsEveryTelemetryRowAndNothingElse(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        insert(jdbc, "hot-1", "2026-10-02T08:00:00Z", "z2m-temp_kitchen", "TELEMETRY", "{\"temperature\": 68.2, \"humidity\": 41}");
        insert(jdbc, "hot-2", "2026-10-03T08:00:00Z", "z2m-temp_mech_room", "TELEMETRY", "{\"temperature\": 61.0}");
        insert(jdbc, "cam-1", "2026-10-03T09:00:00Z", "front_door", "DETECTION_NEW", "{\"label\": \"person\"}");

        List<JsonNode> rows = exportJson(service(jdbc, dir), all(TelemetryExportService.Format.JSONL_GZ));

        assertEquals(List.of("hot-1", "hot-2"), ids(rows), "only TELEMETRY rows, in time order, no camera events");
        assertEquals(68.2, rows.get(0).get("payload").get("temperature").asDouble());
        assertEquals("z2m-temp_kitchen", rows.get(0).get("deviceId").asText());
    }

    @Test
    void coldOnlyReadsAMonthlyArchiveWhenPostgresHoldsNothing(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        writeMonthArchive(dir, "2026-08",
            jsonLine("cold-1", "2026-08-05T01:00:00Z", "z2m-temp_kitchen", "{\"temperature\": 70.1}"),
            jsonLine("cold-2", "2026-08-06T01:00:00Z", "z2m-temp_kitchen", "{\"temperature\": 70.4}"));

        List<JsonNode> rows = exportJson(service(jdbc, dir), all(TelemetryExportService.Format.JSONL_GZ));

        assertEquals(List.of("cold-1", "cold-2"), ids(rows));
    }

    @Test
    void hotAndColdTogetherSpanMonthsInOrderAndCountEachRowOnce(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        // cold: month archive, rows already deleted from Postgres
        writeMonthArchive(dir, "2026-08",
            jsonLine("a-1", "2026-08-05T01:00:00Z", "d1", "{\"temperature\": 1}"));
        // overlap 1: the same August row is ALSO in the incremental copy
        writeIncremental(dir, "2026-08-05",
            jsonLine("a-1", "2026-08-05T01:00:00Z", "d1", "{\"temperature\": 1}"));
        // hot: Postgres, September
        insert(jdbc, "h-1", "2026-09-10T01:00:00Z", "d1", "TELEMETRY", "{\"temperature\": 2}");
        // overlap 2: the same September row is ALSO in the incremental copy, plus one row that exists only there
        writeIncremental(dir, "2026-09-10",
            jsonLine("h-1", "2026-09-10T01:00:00Z", "d1", "{\"temperature\": 2}"),
            jsonLine("only-incremental", "2026-09-10T02:00:00Z", "d1", "{\"temperature\": 3}"));
        // overlap 3: a month archive written just before a failed delete, so the row is in the archive AND Postgres
        writeMonthArchive(dir, "2026-10",
            jsonLine("o-1", "2026-10-01T00:30:00Z", "d1", "{\"temperature\": 4}"));
        insert(jdbc, "o-1", "2026-10-01T00:30:00Z", "d1", "TELEMETRY", "{\"temperature\": 4}");

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        TelemetryExportService.Result result = service(jdbc, dir).export(all(TelemetryExportService.Format.JSONL), raw);
        List<JsonNode> rows = exportJson(service(jdbc, dir), all(TelemetryExportService.Format.JSONL));

        assertEquals(List.of("a-1", "h-1", "only-incremental", "o-1"), ids(rows),
            "months ascending, each event_id exactly once");
        assertEquals(4, result.rows());
        assertEquals(3, result.duplicatesSkipped(), "three rows were present in more than one source");
        assertEquals(0, result.malformedSkipped());
    }

    @Test
    void anEmptyRangeIsAValidEmptyDownload(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        insert(jdbc, "hot-1", "2026-10-02T08:00:00Z", "d1", "TELEMETRY", "{\"temperature\": 1}");

        var query = new TelemetryExportService.Query(Instant.parse("2025-01-01T00:00:00Z"), Instant.parse("2025-02-01T00:00:00Z"),
            Set.of(), Set.of(), TelemetryExportService.Format.JSONL_GZ);
        assertEquals(List.of(), exportJson(service(jdbc, dir), query), "valid gzip, zero rows");

        // and with nothing anywhere at all (no rows, no archive directory), with the default everything range
        jdbc.execute("TRUNCATE TABLE cabin_event");
        assertEquals(List.of(), exportJson(service(jdbc, dir.resolve("missing")), all(TelemetryExportService.Format.JSONL_GZ)));

        // CSV with no rows is just the header
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service(jdbc, dir).export(all(TelemetryExportService.Format.CSV), out);
        assertEquals("time,eventId,deviceId,severity,field,value\r\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void fromIsInclusiveToIsExclusiveAndRangesCutAcrossSources(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        writeMonthArchive(dir, "2026-08",
            jsonLine("c-1", "2026-08-31T23:59:59Z", "d1", "{\"t\": 1}"),
            jsonLine("c-2", "2026-08-15T00:00:00Z", "d1", "{\"t\": 2}"));
        insert(jdbc, "h-1", "2026-09-01T00:00:00Z", "d1", "TELEMETRY", "{\"t\": 3}");
        insert(jdbc, "h-2", "2026-09-02T00:00:00Z", "d1", "TELEMETRY", "{\"t\": 4}");

        var query = new TelemetryExportService.Query(Instant.parse("2026-08-31T00:00:00Z"), Instant.parse("2026-09-02T00:00:00Z"),
            Set.of(), Set.of(), TelemetryExportService.Format.JSONL);
        assertEquals(List.of("c-1", "h-1"), ids(exportJson(service(jdbc, dir), query)));
    }

    @Test
    void deviceAndFieldFiltersApplyToLiveAndArchivedRows(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        writeMonthArchive(dir, "2026-08",
            jsonLine("c-1", "2026-08-05T00:00:00Z", "d1", "{\"temperature\": 1, \"humidity\": 9}"),
            jsonLine("c-2", "2026-08-05T01:00:00Z", "d2", "{\"temperature\": 2}"));
        insert(jdbc, "h-1", "2026-09-05T00:00:00Z", "d1", "TELEMETRY", "{\"temperature\": 3, \"battery\": 80}");
        insert(jdbc, "h-2", "2026-09-05T01:00:00Z", "d1", "TELEMETRY", "{\"battery\": 79}");
        insert(jdbc, "h-3", "2026-09-05T02:00:00Z", "d2", "TELEMETRY", "{\"temperature\": 5}");

        var query = new TelemetryExportService.Query(null, null, Set.of("d1"), Set.of("temperature"), TelemetryExportService.Format.JSONL);
        List<JsonNode> rows = exportJson(service(jdbc, dir), query);

        assertEquals(List.of("c-1", "h-1"), ids(rows), "d1 only, and h-2 has no temperature so it is dropped");
        assertEquals(Set.of("temperature"), Set.copyOf(iterable(rows.get(0).get("payload").fieldNames())), "payload cut to the requested field");
        assertEquals(Set.of("temperature"), Set.copyOf(iterable(rows.get(1).get("payload").fieldNames())));
    }

    @Test
    void csvIsLongFormatWithOneLinePerField(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        insert(jdbc, "h-1", "2026-09-05T00:00:00Z", "d,1", "TELEMETRY", "{\"temperature\": 3.5, \"note\": \"a \\\"b\\\"\"}");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service(jdbc, dir).export(all(TelemetryExportService.Format.CSV_GZ), out);
        String csv = new String(new GZIPInputStream(new java.io.ByteArrayInputStream(out.toByteArray())).readAllBytes(), StandardCharsets.UTF_8);

        String[] lines = csv.split("\r\n");
        assertEquals("time,eventId,deviceId,severity,field,value", lines[0]);
        assertEquals(3, lines.length, "header plus one line per payload field");
        assertTrue(lines[1].startsWith("2026-09-05T00:00:00Z,h-1,\"d,1\",INFO,"), "a comma in a value is quoted: " + lines[1]);
        assertTrue(csv.contains("note,\"a \"\"b\"\"\""), "a quote in a value is doubled");
    }

    @Test
    void aLargeRangeIsPagedAndLosesNothing(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        int n = TelemetryExportService.PAGE_SIZE * 2 + 17;
        // many rows share one timestamp, which is what a keyset page boundary has to survive
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            SELECT 'p-' || lpad(g::text, 6, '0'), TIMESTAMPTZ '2026-09-05T00:00:00Z' + ((g / 100) * interval '1 second'),
                   'd1', 'TELEMETRY', 'INFO', '{"t": 1}'::jsonb
            FROM generate_series(1, ?) g
            """, n);

        List<JsonNode> rows = exportJson(service(jdbc, dir), all(TelemetryExportService.Format.JSONL));

        assertEquals(n, rows.size());
        assertEquals(n, Set.copyOf(ids(rows)).size(), "no row repeated across page boundaries");
    }

    @Test
    void aMalformedArchiveLineIsSkippedAndCountedNotFatal(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        writeIncremental(dir, "2026-09-05",
            jsonLine("i-1", "2026-09-05T00:00:00Z", "d1", "{\"t\": 1}"),
            "{\"eventId\":\"i-2\",\"time\":\"2026-09-05T00:0");  // a crash mid-append leaves a cut-off last line

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        TelemetryExportService.Result result = service(jdbc, dir).export(all(TelemetryExportService.Format.JSONL), out);

        assertEquals(1, result.rows());
        assertEquals(1, result.malformedSkipped());
    }

    @Test
    void monthSplitRequestsEqualOneRequestForTheWholeRange(@TempDir Path dir) throws Exception {
        JdbcTemplate jdbc = jdbc();
        writeMonthArchive(dir, "2026-08", jsonLine("c-1", "2026-08-05T00:00:00Z", "d1", "{\"t\": 1}"));
        insert(jdbc, "h-1", "2026-09-05T00:00:00Z", "d1", "TELEMETRY", "{\"t\": 2}");
        insert(jdbc, "h-2", "2026-10-05T00:00:00Z", "d1", "TELEMETRY", "{\"t\": 3}");
        TelemetryExportService service = service(jdbc, dir);

        List<String> whole = ids(exportJson(service, all(TelemetryExportService.Format.JSONL)));
        List<String> split = new ArrayList<>();
        for (YearMonth m = YearMonth.of(2026, 8); !m.isAfter(YearMonth.of(2026, 10)); m = m.plusMonths(1)) {
            var q = new TelemetryExportService.Query(m.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(),
                m.plusMonths(1).atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant(), Set.of(), Set.of(),
                TelemetryExportService.Format.JSONL);
            split.addAll(ids(exportJson(service, q)));
        }
        assertEquals(whole, split);
    }

    private static <T> List<T> iterable(java.util.Iterator<T> it) {
        List<T> out = new ArrayList<>();
        it.forEachRemaining(out::add);
        return out;
    }
}
