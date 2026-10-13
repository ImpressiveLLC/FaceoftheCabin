package com.cabin.orchestrator.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * W-62: the backend half of the in-app Trim control (Nate, 2026-10-13: "cut it down from total or turn
 * it up to remove more ... showing how much that action will reduce total storage used ... then Go").
 *
 * This container cannot delete host files, so it never does. It shares a small spool folder with a root
 * service on the host (scripts/host/cabin-storage-trim-agent), inside the one bind mount it already
 * writes to (/storage/archives/cabin_event):
 *
 *   plan.json    written by the agent: every dated backup folder and its size (read here, served to the UI)
 *   request.env  written here when the administrator presses Go: requestId and keep.&lt;target&gt;=N lines
 *   result.json  written by the agent when it has run the request
 *
 * Everything is validated here AND again by the agent, and the agent runs the same tested tool
 * (cabin-storage-prune) that refuses telemetry and database paths, so a bug on this side cannot remove
 * anything the tool would not. Only dated-dirs targets are offered, N is at least 1 (the newest folder is
 * never removed), and one request is in flight at a time.
 */
@Service
public class StorageTrimService {

    private static final Logger log = LoggerFactory.getLogger(StorageTrimService.class);
    private static final Pattern TARGET_ID = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    /** A plan older than this is shown but flagged: the nightly backups add a folder a day. */
    static final Duration STALE_AFTER = Duration.ofHours(12);

    private final StorageMonitorService monitor;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${cabin.storage.trimSpoolDir:/app/archives/cabin_event/.trim}")
    private String spoolDir;

    @Autowired
    public StorageTrimService(StorageMonitorService monitor) {
        this.monitor = monitor;
    }

    /** Tests: point at a scratch folder. */
    StorageTrimService(StorageMonitorService monitor, String spoolDir) {
        this.monitor = monitor;
        this.spoolDir = spoolDir;
    }

    public static class TrimException extends RuntimeException {
        private final HttpStatus status;
        public TrimException(HttpStatus status, String message) { super(message); this.status = status; }
        public HttpStatus status() { return status; }
    }

    /** What the Trim widget needs: the plan, the disk it will change, and the last result. */
    public Map<String, Object> view() {
        Map<String, Object> out = new LinkedHashMap<>();
        Path plan = Path.of(spoolDir, "plan.json");
        out.put("pending", Files.exists(Path.of(spoolDir, "request.env")));
        out.put("disk", hddUsage());
        Map<String, Object> planJson = readJson(plan);
        if (planJson == null) {
            out.put("installed", false);
            out.put("message", "The host trim service has not produced a plan yet. It is installed once on the M920q "
                + "(docs/STORAGE_ARCHITECTURE.md, \"Trim control\"); until then use cabin-storage-prune on the host.");
            return out;
        }
        out.put("installed", true);
        Instant plannedAt = parseInstant(planJson.get("plannedAt"));
        out.put("plannedAt", plannedAt == null ? null : plannedAt.toString());
        out.put("planAgeSeconds", plannedAt == null ? null : Duration.between(plannedAt, Instant.now()).toSeconds());
        out.put("stale", plannedAt == null || Duration.between(plannedAt, Instant.now()).compareTo(STALE_AFTER) > 0);
        out.put("sizesMeasured", Boolean.TRUE.equals(planJson.get("sizesMeasured")));
        out.put("targets", trimmableTargets(planJson));
        Map<String, Object> result = readJson(Path.of(spoolDir, "result.json"));
        out.put("lastResult", result);
        return out;
    }

    /**
     * Writes the request the host agent will pick up. {@code keep} maps target id to the number of newest
     * folders to keep. Returns the request id.
     */
    public String request(Map<String, Integer> keep) {
        if (keep == null || keep.isEmpty()) throw new TrimException(HttpStatus.BAD_REQUEST, "Name at least one folder to trim");
        Map<String, Object> planJson = readJson(Path.of(spoolDir, "plan.json"));
        if (planJson == null) throw new TrimException(HttpStatus.SERVICE_UNAVAILABLE, "The host trim service has not produced a plan yet");
        Map<String, Map<String, Object>> targets = new LinkedHashMap<>();
        for (Map<String, Object> t : trimmableTargets(planJson)) targets.put(String.valueOf(t.get("id")), t);

        boolean anythingToRemove = false;
        for (Map.Entry<String, Integer> e : keep.entrySet()) {
            String id = e.getKey();
            if (id == null || !TARGET_ID.matcher(id).matches() || !targets.containsKey(id)) {
                throw new TrimException(HttpStatus.BAD_REQUEST, "Not a trimmable folder: " + id);
            }
            int count = ((Number) targets.get(id).get("count")).intValue();
            Integer n = e.getValue();
            if (n == null || n < 1) throw new TrimException(HttpStatus.BAD_REQUEST, "Keep at least 1 snapshot of " + id + " (the newest is never removed)");
            if (n > count) throw new TrimException(HttpStatus.BAD_REQUEST, id + " has only " + count + " snapshots");
            if (n < count) anythingToRemove = true;
        }
        if (!anythingToRemove) throw new TrimException(HttpStatus.BAD_REQUEST, "Nothing would be removed at those settings");

        Path request = Path.of(spoolDir, "request.env");
        if (Files.exists(request)) throw new TrimException(HttpStatus.CONFLICT, "A trim is already waiting for the host or running");

        String requestId = UUID.randomUUID().toString();
        StringBuilder body = new StringBuilder("requestId=").append(requestId).append('\n')
            .append("requestedAt=").append(Instant.now()).append('\n');
        keep.forEach((id, n) -> body.append("keep.").append(id).append('=').append(n).append('\n'));
        try {
            Path tmp = Files.createTempFile(Path.of(spoolDir), "request-", ".tmp");
            Files.writeString(tmp, body.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, request, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, request);
            }
        } catch (IOException ex) {
            log.warn("Could not write trim request: {}", ex.toString());
            throw new TrimException(HttpStatus.SERVICE_UNAVAILABLE, "Could not write the request for the host trim service");
        }
        log.info("Storage trim requested {} keep={}", requestId, keep);
        return requestId;
    }

    // ---- helpers ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> trimmableTargets(Map<String, Object> planJson) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object raw = planJson.get("targets");
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> t = (Map<String, Object>) m;
            if (!"dated-dirs".equals(t.get("kind")) || !"ok".equals(t.get("status"))) continue;
            if (!(t.get("snapshots") instanceof List<?> snaps) || snaps.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", t.get("id"));
            row.put("path", t.get("path"));
            row.put("keep", t.get("keep"));
            row.put("count", snaps.size());
            row.put("snapshots", snaps);
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> hddUsage() {
        for (Map<String, Object> row : monitor.snapshot()) {
            if ("storage-hdd".equals(row.get("mount")) && Boolean.TRUE.equals(row.get("available"))) {
                Map<String, Object> d = new LinkedHashMap<>();
                d.put("usedPercent", row.get("usedPercent"));
                d.put("freeGb", row.get("freeGb"));
                d.put("totalGb", row.get("totalGb"));
                return d;
            }
        }
        return null;
    }

    private Map<String, Object> readJson(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.isSymbolicLink(p) || Files.size(p) > 2_000_000) return null;
            return mapper.readValue(Files.readString(p, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            log.debug("Could not read {}: {}", p, e.toString());
            return null;
        }
    }

    private static Instant parseInstant(Object v) {
        try { return v == null ? null : Instant.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }
}
