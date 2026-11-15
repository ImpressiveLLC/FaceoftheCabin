package com.cabin.orchestrator.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Streams the full TELEMETRY history, hot and cold together, as one download (added 2026-11-14 at the
 * owner's request: at least three years of full monitoring data for an HVAC review, fetched by a scripted
 * call from whichever machine he is on).
 *
 * <p>TELEMETRY lives in three places (see TelemetryArchivalService and docs/STORAGE_ARCHITECTURE.md):
 * the live Postgres table {@code cabin_event}, monthly {@code YYYY-MM.jsonl.gz} archives (written, then the
 * rows are deleted from Postgres), and the hourly additive {@code incremental/incremental-YYYY-MM-DD.jsonl}
 * copy. The same row can be in more than one of them (an incremental row that is also still in Postgres,
 * or a month archive written just before a failed delete), so the export de-duplicates by {@code event_id}.
 *
 * <p>Nothing is held in memory beyond one calendar month's set of event ids: the range is processed one UTC
 * month at a time, each source is read as a stream (Postgres by keyset pages, files line by line), and the
 * id set is dropped when the month ends. Output is therefore grouped by month, ascending; inside a month it
 * is the archive rows, then the live rows, then any incremental-only rows, each in time order. Sort by
 * {@code time} after loading if strict order matters. Months do not overlap, so a request split by month
 * (what scripts/export_telemetry.py does, to make a download resumable) yields exactly the rows of one
 * request for the whole range.
 *
 * <p>This class never writes to, deletes from, or reads any table other than {@code cabin_event}.
 */
@Service
public class TelemetryExportService {

    private static final Logger log = LoggerFactory.getLogger(TelemetryExportService.class);

    /** Rows per Postgres page. Keyset pagination, so memory stays flat and no transaction has to stay open. */
    static final int PAGE_SIZE = 5000;

    /**
     * An incremental file is named for the day it was written, and its rows can be older than that day (after a gap in
     * the job, the first run after the gap exports everything since the watermark). A month therefore also scans the
     * files written this many days after it ended.
     */
    static final int INCREMENTAL_LOOKAHEAD_DAYS = 7;

    public enum Format {
        JSONL("jsonl", "application/x-ndjson", false, false),
        JSONL_GZ("jsonl.gz", "application/gzip", true, false),
        CSV("csv", "text/csv", false, true),
        CSV_GZ("csv.gz", "application/gzip", true, true);

        private final String id;
        private final String contentType;
        private final boolean gzip;
        private final boolean csv;

        Format(String id, String contentType, boolean gzip, boolean csv) {
            this.id = id;
            this.contentType = contentType;
            this.gzip = gzip;
            this.csv = csv;
        }

        public String id() { return id; }
        public String contentType() { return contentType; }

        public static Format parse(String value) {
            if (value == null || value.isBlank()) return JSONL_GZ;
            for (Format f : values()) if (f.id.equalsIgnoreCase(value.trim())) return f;
            throw new IllegalArgumentException("format must be one of jsonl.gz, jsonl, csv.gz, csv");
        }
    }

    /**
     * @param from inclusive, or null for the earliest row there is
     * @param to exclusive, or null for now
     * @param deviceIds exact device ids, or empty for every device
     * @param fields payload keys to keep, or empty for the whole payload; rows with none of them are dropped
     */
    public record Query(Instant from, Instant to, Set<String> deviceIds, Set<String> fields, Format format) {
        public Query {
            deviceIds = deviceIds == null ? Set.of() : Set.copyOf(deviceIds);
            fields = fields == null ? Set.of() : Set.copyOf(fields);
            format = format == null ? Format.JSONL_GZ : format;
        }
    }

    /** Counts only, safe to log and to quote: never telemetry values. */
    public record Result(long rows, long duplicatesSkipped, long malformedSkipped) {}

    @Value("${cabin.telemetryArchival.archiveDir:/app/archives/cabin_event}")
    private String archiveDir;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public TelemetryExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One exported row, whichever source it came from. */
    private record Row(String eventId, Instant time, String deviceId, String eventType, String severity, JsonNode payload) {}

    /** Mutable per-export counters. */
    private static final class Counters {
        long rows;
        long duplicates;
        long malformed;
    }

    /**
     * Writes the export to {@code out} (not closed here; a gzip trailer is finished and flushed). An {@link IOException}
     * from a source file, or any failure partway, propagates so the caller aborts the response: a truncated download must
     * never look complete.
     */
    public Result export(Query query, OutputStream out) throws IOException {
        OutputStream target = query.format().gzip ? new GZIPOutputStream(out, 64 * 1024) : out;
        Writer writer = new BufferedWriter(new OutputStreamWriter(target, StandardCharsets.UTF_8), 64 * 1024);
        Counters counters = new Counters();

        if (query.format().csv) writer.write("time,eventId,deviceId,severity,field,value\r\n");

        Instant to = query.to() != null ? query.to() : Instant.now();
        Instant from = query.from() != null ? query.from() : earliest();
        if (from != null && from.isBefore(to)) {
            YearMonth last = YearMonth.from(to.minusNanos(1).atZone(ZoneOffset.UTC));
            for (YearMonth month = YearMonth.from(from.atZone(ZoneOffset.UTC)); !month.isAfter(last); month = month.plusMonths(1)) {
                Instant start = max(from, month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                Instant end = min(to, month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                if (start.isBefore(end)) exportMonth(month, start, end, query, writer, counters);
            }
        }

        writer.flush();
        if (target instanceof GZIPOutputStream gz) gz.finish();
        target.flush();
        log.info("Telemetry export finished: {} row(s), {} duplicate(s) skipped, {} malformed archive line(s) skipped",
            counters.rows, counters.duplicates, counters.malformed);
        return new Result(counters.rows, counters.duplicates, counters.malformed);
    }

    private void exportMonth(YearMonth month, Instant start, Instant end, Query query, Writer writer, Counters counters)
            throws IOException {
        Set<String> seen = new HashSet<>();

        Path archive = Path.of(archiveDir, month + ".jsonl.gz");
        if (Files.isRegularFile(archive)) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new GZIPInputStream(Files.newInputStream(archive)), StandardCharsets.UTF_8))) {
                emitLines(reader, start, end, query, seen, writer, counters);
            }
        }

        emitHot(start, end, query, seen, writer, counters);

        LocalDate day = month.atDay(1);
        LocalDate lastDay = month.atEndOfMonth().plusDays(INCREMENTAL_LOOKAHEAD_DAYS);
        for (; !day.isAfter(lastDay); day = day.plusDays(1)) {
            Path incremental = Path.of(archiveDir, "incremental", "incremental-" + day + ".jsonl");
            if (!Files.isRegularFile(incremental)) continue;
            try (BufferedReader reader = Files.newBufferedReader(incremental, StandardCharsets.UTF_8)) {
                emitLines(reader, start, end, query, seen, writer, counters);
            }
        }
    }

    private void emitLines(BufferedReader reader, Instant start, Instant end, Query query, Set<String> seen,
                           Writer writer, Counters counters) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            Row row = parseLine(line);
            if (row == null) {
                counters.malformed++;
                continue;
            }
            if (row.time().isBefore(start) || !row.time().isBefore(end)) continue;
            if (!"TELEMETRY".equals(row.eventType())) continue;
            offer(row, query, seen, writer, counters);
        }
    }

    private Row parseLine(String line) {
        try {
            JsonNode n = mapper.readTree(line);
            String id = text(n, "eventId");
            String time = text(n, "time");
            if (id == null || time == null) return null;
            JsonNode payload = n.get("payload");
            return new Row(id, Instant.parse(time), text(n, "deviceId"), text(n, "eventType"), text(n, "severity"),
                payload == null ? mapper.createObjectNode() : payload);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    /** Keyset-paged read of the live table, ordered by (time, event_id), so memory stays flat for any range. */
    private void emitHot(Instant start, Instant end, Query query, Set<String> seen, Writer writer, Counters counters)
            throws IOException {
        StringBuilder sql = new StringBuilder("""
            SELECT event_id, time, device_id, event_type, severity, payload::text AS payload FROM cabin_event
            WHERE event_type = 'TELEMETRY' AND time >= ? AND time < ? AND (time, event_id) > (?, ?)
            """);
        if (!query.deviceIds().isEmpty()) sql.append(" AND device_id = ANY (?)");
        if (!query.fields().isEmpty()) sql.append(" AND jsonb_exists_any(payload, ?)");
        sql.append(" ORDER BY time, event_id LIMIT ").append(PAGE_SIZE);
        String statement = sql.toString();

        // One microsecond before the window, so a row exactly at the window start is on the first page.
        Timestamp lastTime = Timestamp.from(start.minusNanos(1000));
        String lastId = "";
        while (true) {
            final Timestamp afterTime = lastTime;
            final String afterId = lastId;
            List<Object[]> page = jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(statement);
                int i = 1;
                ps.setTimestamp(i++, Timestamp.from(start));
                ps.setTimestamp(i++, Timestamp.from(end));
                ps.setTimestamp(i++, afterTime);
                ps.setString(i++, afterId);
                if (!query.deviceIds().isEmpty()) {
                    ps.setArray(i++, con.createArrayOf("text", query.deviceIds().toArray()));
                }
                if (!query.fields().isEmpty()) {
                    ps.setArray(i, con.createArrayOf("text", query.fields().toArray()));
                }
                return ps;
            }, (rs, n) -> new Object[] {
                rs.getString("event_id"), rs.getTimestamp("time"), rs.getString("device_id"),
                rs.getString("event_type"), rs.getString("severity"), rs.getString("payload")
            });

            for (Object[] r : page) {
                Timestamp t = (Timestamp) r[1];
                JsonNode payload;
                try {
                    payload = r[5] == null ? mapper.createObjectNode() : mapper.readTree((String) r[5]);
                } catch (Exception e) {
                    payload = mapper.createObjectNode();
                }
                offer(new Row((String) r[0], t.toInstant(), (String) r[2], (String) r[3], (String) r[4], payload),
                    query, seen, writer, counters);
                lastTime = t;
                lastId = (String) r[0];
            }
            if (page.size() < PAGE_SIZE) return;
        }
    }

    /** Applies the device and field filters, drops an event id already written this month, then writes the row. */
    private void offer(Row row, Query query, Set<String> seen, Writer writer, Counters counters) throws IOException {
        if (!query.deviceIds().isEmpty() && !query.deviceIds().contains(row.deviceId())) return;
        JsonNode payload = row.payload();
        if (!query.fields().isEmpty()) {
            ObjectNode kept = mapper.createObjectNode();
            for (String field : query.fields()) {
                if (payload != null && payload.has(field)) kept.set(field, payload.get(field));
            }
            if (kept.isEmpty()) return;
            payload = kept;
        }
        if (!seen.add(row.eventId())) {
            counters.duplicates++;
            return;
        }
        if (query.format().csv) writeCsv(row, payload, writer);
        else writeJson(row, payload, writer);
        counters.rows++;
    }

    private void writeJson(Row row, JsonNode payload, Writer writer) throws IOException {
        ObjectNode out = mapper.createObjectNode();
        out.put("eventId", row.eventId());
        out.put("time", row.time().toString());
        out.put("deviceId", row.deviceId());
        out.put("eventType", row.eventType());
        out.put("severity", row.severity());
        out.set("payload", payload == null ? mapper.createObjectNode() : payload);
        writer.write(mapper.writeValueAsString(out));
        writer.write('\n');
    }

    /** Long format: one line per top-level payload field, so the header is fixed and the stream needs no schema scan. */
    private void writeCsv(Row row, JsonNode payload, Writer writer) throws IOException {
        if (payload == null || !payload.isObject()) return;
        Iterator<java.util.Map.Entry<String, JsonNode>> fields = payload.fields();
        while (fields.hasNext()) {
            var field = fields.next();
            JsonNode v = field.getValue();
            String value = v.isNull() ? "" : (v.isContainerNode() ? v.toString() : v.asText());
            writer.write(csv(row.time().toString()));
            writer.write(',');
            writer.write(csv(row.eventId()));
            writer.write(',');
            writer.write(csv(row.deviceId()));
            writer.write(',');
            writer.write(csv(row.severity()));
            writer.write(',');
            writer.write(csv(field.getKey()));
            writer.write(',');
            writer.write(csv(value));
            writer.write("\r\n");
        }
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    /** The earliest TELEMETRY row anywhere: the live table, a monthly archive file name, or an incremental file name. */
    private Instant earliest() throws IOException {
        Instant best = null;
        Timestamp live = jdbc.queryForObject("SELECT min(time) FROM cabin_event WHERE event_type = 'TELEMETRY'", Timestamp.class);
        if (live != null) best = live.toInstant();

        Path dir = Path.of(archiveDir);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                List<String> names = new ArrayList<>(files.map(p -> p.getFileName().toString()).toList());
                for (String name : names) {
                    if (name.matches("\\d{4}-\\d{2}\\.jsonl\\.gz")) {
                        best = min(best, YearMonth.parse(name.substring(0, 7)).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant());
                    }
                }
            }
            Path incremental = dir.resolve("incremental");
            if (Files.isDirectory(incremental)) {
                try (Stream<Path> files = Files.list(incremental)) {
                    for (String name : files.map(p -> p.getFileName().toString()).toList()) {
                        if (name.matches("incremental-\\d{4}-\\d{2}-\\d{2}\\.jsonl")) {
                            best = min(best, LocalDate.parse(name.substring(12, 22)).atStartOfDay(ZoneOffset.UTC).toInstant());
                        }
                    }
                }
            }
        }
        return best;
    }

    private static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    private static Instant min(Instant a, Instant b) {
        if (a == null) return b;
        return a.isBefore(b) ? a : b;
    }
}
