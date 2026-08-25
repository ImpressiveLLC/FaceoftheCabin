package com.cabin.orchestrator.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Tiered retention for cabin_event's TELEMETRY rows (sensor readings --
 * temp, humidity, water_leak, battery, and anything else devices report
 * this way) -- added 2026-08-25 at the user's request: keep a rolling hot
 * window queryable in Postgres (on the M920q's NVMe SSD) for analytics
 * without unzipping anything, archive whole months older than that to a
 * gzipped JSONL file on /storage (the real, already-rotational HDD this
 * deployment already uses for every other bulk/long-term artifact --
 * camera recordings, Node-RED data, HA config), then delete those rows
 * from the live table.
 *
 * Scoped to event_type = TELEMETRY specifically, not every cabin_event row
 * -- camera events (DETECTION_ and MOTION_ prefixed; Frigate owns its own
 * clip retention), workflow/alert events (WORKFLOW_ prefixed and
 * AUTOMATION_ALERT; operational/audit history with a different
 * useful lifetime), and discrete state-change events (SECURITY_ARMED_CHANGED,
 * PRESENCE_CHANGED, KIDDE_CO_ALARM_CHANGED) all have different retention
 * needs than a continuous sensor reading and are deliberately left alone
 * here -- broadening this scope is a real, separate decision, not an
 * oversight.
 *
 * Data volume checked live before designing this, not assumed: the whole
 * table was 33MB for 36k rows after 19 days of real production traffic
 * (~2,200 events/day) -- a 3-month hot window is on the order of a couple
 * hundred MB, trivial to keep live. This is why there's no need for
 * anything fancier than gzip + JSONL.
 */
@Service
public class TelemetryArchivalService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryArchivalService.class);
    static final String ARCHIVED_EVENT_TYPE = "TELEMETRY";

    @Value("${cabin.telemetryArchival.enabled:true}")
    private boolean enabled;

    @Value("${cabin.telemetryArchival.hotRetentionMonths:3}")
    private int hotRetentionMonths;

    @Value("${cabin.telemetryArchival.archiveDir:/app/archives/cabin_event}")
    private String archiveDir;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public TelemetryArchivalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs monthly (2am on the 1st, matching "on the first of every month"
     * -- the exact cadence asked for) and archives every whole calendar
     * month that has fully aged out of the hot window. Not just "last
     * month" -- if a run is ever missed (downtime, a config change), this
     * catches up every eligible month, not just the most recent one.
     */
    @Scheduled(cron = "${cabin.telemetryArchival.cron:0 0 2 1 * *}")
    public void archiveOldMonths() {
        if (!enabled) return;
        YearMonth cutoff = YearMonth.now(ZoneOffset.UTC).minusMonths(hotRetentionMonths);
        YearMonth earliest = earliestEventMonth();
        if (earliest == null) return;
        for (YearMonth month = earliest; month.isBefore(cutoff); month = month.plusMonths(1)) {
            archiveMonth(month);
        }
    }

    private YearMonth earliestEventMonth() {
        Timestamp earliest = jdbc.queryForObject(
            "SELECT min(time) FROM cabin_event WHERE event_type = ?", Timestamp.class, ARCHIVED_EVENT_TYPE);
        return earliest == null ? null : YearMonth.from(earliest.toInstant().atZone(ZoneOffset.UTC));
    }

    /**
     * Exports one whole calendar month of TELEMETRY rows to a gzipped
     * JSONL file, then deletes them from the live table. Idempotent by
     * file presence -- a month whose archive file already exists is
     * skipped entirely (not re-exported, not re-deleted-from, since
     * there'd be nothing left to delete anyway after the first run).
     */
    void archiveMonth(YearMonth month) {
        try {
            Path dir = Path.of(archiveDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(month + ".jsonl.gz");
            if (Files.exists(file)) {
                log.debug("Telemetry archive for {} already exists at {}, skipping", month, file);
                return;
            }

            Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();

            List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT event_id, time, device_id, event_type, severity, payload FROM cabin_event
                WHERE event_type = ? AND time >= ? AND time < ? ORDER BY time
                """,
                ARCHIVED_EVENT_TYPE, Timestamp.from(start), Timestamp.from(end));

            if (rows.isEmpty()) {
                log.info("No {} events for {} -- nothing to archive", ARCHIVED_EVENT_TYPE, month);
                return;
            }

            writeGzippedJsonl(file, rows);

            int deleted = jdbc.update(
                "DELETE FROM cabin_event WHERE event_type = ? AND time >= ? AND time < ?",
                ARCHIVED_EVENT_TYPE, Timestamp.from(start), Timestamp.from(end));

            log.info("Archived {} {} row(s) for {} to {} and removed them from the live table (deleted {} row(s))",
                rows.size(), ARCHIVED_EVENT_TYPE, month, file, deleted);
        } catch (IOException e) {
            log.error("Failed to archive telemetry for {} -- leaving the live rows in place, will retry next run", month, e);
        }
    }

    /**
     * Writes to a sibling .tmp file and only atomically moves it onto the
     * real path once the export has fully succeeded -- a crash or failure
     * mid-write must never leave a partial file at the real path, since
     * that would make archiveMonth()'s exists()-based idempotency check
     * wrongly treat an incomplete export as "already done" on the next run.
     */
    private void writeGzippedJsonl(Path file, List<Map<String, Object>> rows) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try (var out = Files.newOutputStream(tmp);
             var gzip = new GZIPOutputStream(out);
             Writer writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            for (Map<String, Object> row : rows) {
                writer.write(mapper.writeValueAsString(rowToExportMap(row)));
                writer.write("\n");
            }
        }
        Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * device_id is nullable in the real schema, so this can't use Map.of()
     * (throws on a null value) -- same reasoning CabinEventService's own
     * row-mapping code already works around for the same column.
     */
    private Map<String, Object> rowToExportMap(Map<String, Object> row) {
        Object time = row.get("time");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", row.get("event_id"));
        out.put("time", time instanceof Timestamp t ? t.toInstant().toString() : String.valueOf(time));
        out.put("deviceId", row.get("device_id"));
        out.put("eventType", row.get("event_type"));
        out.put("severity", row.get("severity"));
        out.put("payload", parsePayload(row.get("payload")));
        return out;
    }

    // payload comes back from the JDBC driver as a PGobject (jsonb), not a
    // String -- same quirk CabinEventService.parseJson() already handles.
    // Parsed back into a real tree here (not left as an escaped string) so
    // it nests as genuine JSON in the export, not double-encoded text.
    private Object parsePayload(Object payload) {
        if (payload == null) return Map.of();
        String json = payload.toString();
        if (json.isBlank()) return Map.of();
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
