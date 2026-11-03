package com.cabin.orchestrator.recovery;

import com.cabin.orchestrator.alerts.ActiveAlert;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Backend half of the recovery check and component heartbeats (docs/governance/proposals/2026-11-03-recovery-heartbeat-service.md).
 *
 * The work is done by a root service on the host, outside Docker (scripts/host/cabin-recovery-heartbeat), which writes
 * {@code state.json} into a spool folder inside the bind mount this container already has
 * ({@code /var/lib/cabin-recovery}, mounted read-only at {@code /app/recovery}). This class only READS that
 * file, never writes the spool, and turns it into two things: the Monitoring section's view and the current alerts that
 * feed the Critical banner.
 *
 * The file is untrusted input from the backend's point of view: it must be a regular file (no symlink), at most
 * {@value #MAX_BYTES} bytes, and only whitelisted fields are copied out with their lengths bounded.
 *
 * Off by default ({@code cabin.recovery.enabled}) so a clone, a dev machine or the demo is not nagged about a service it
 * never installed; the M920q compose file turns it on. When on, a missing file is a WARN ("not installed") and a stale
 * file is CRITICAL ("recovery service not reporting"): the very thing the service exists to catch is it going quiet.
 */
@Service
public class RecoveryHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(RecoveryHeartbeatService.class);

    static final long MAX_BYTES = 200_000;
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,40}$");
    private static final int MAX_COMPONENTS = 40;
    private static final int MAX_PROBLEMS = 40;
    private static final int MAX_TEXT = 400;

    /** Same commands as docs/MAINTENANCE.md "Recovery check"; the Monitoring section shows them with a copy button. */
    static final String INSTALL_COMMANDS = String.join("\n",
        "git -C ~/repos/FaceoftheCabin pull --ff-only origin main && cd ~/repos/FaceoftheCabin",
        "sudo install -m 0755 scripts/host/cabin-recovery-heartbeat /usr/local/bin/cabin-recovery-heartbeat",
        "sudo install -m 0644 scripts/host/cabin-recovery-heartbeat.service /etc/systemd/system/cabin-recovery-heartbeat.service",
        "sudo systemctl daemon-reload && sudo systemctl enable --now cabin-recovery-heartbeat");

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${cabin.recovery.enabled:false}")
    private boolean enabled;

    @Value("${cabin.recovery.stateDir:/app/recovery}")
    private String stateDir;

    /** The host writes every minute; five missed writes in a row is not a blip. */
    @Value("${cabin.recovery.staleAfterSeconds:300}")
    private long staleAfterSeconds;

    @Autowired
    public RecoveryHeartbeatService() {}

    /** Tests (also from the alerts package): point at a scratch folder. */
    public RecoveryHeartbeatService(boolean enabled, String stateDir, long staleAfterSeconds) {
        this.enabled = enabled;
        this.stateDir = stateDir;
        this.staleAfterSeconds = staleAfterSeconds;
    }

    private record Snapshot(Map<String, Object> state, Instant writtenAt, boolean unreadable) {}

    private Snapshot read() {
        Path dir = Path.of(stateDir);
        Path p = dir.resolve("state.json");
        // A symlinked state folder or file would let whoever can write there point this read at another file.
        if (Files.isSymbolicLink(dir)) return new Snapshot(null, null, true);
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return null;
        // Decide it is a regular file BEFORE opening (opening a FIFO would block forever), open without following
        // symlinks, then confirm it is still the same file and read at most MAX_BYTES + 1.
        try {
            BasicFileAttributes before = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!before.isRegularFile() || before.size() > MAX_BYTES) return new Snapshot(null, null, true);
            try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes after = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!after.isRegularFile() || (before.fileKey() != null && !before.fileKey().equals(after.fileKey()))) {
                    return new Snapshot(null, null, true);
                }
                byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) return new Snapshot(null, null, true);
                Map<String, Object> raw = mapper.readValue(new String(bytes, StandardCharsets.UTF_8), new TypeReference<>() {});
                Instant at = instant(raw.get("writtenAt"));
                if (at == null) return new Snapshot(null, null, true);
                return new Snapshot(raw, at, false);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read recovery state: {}", e.toString());
            return new Snapshot(null, null, true);
        }
    }

    /** What the Monitoring section shows. {@code state} is one of disabled, not-installed, silent, stopped, degraded, ok. */
    public Map<String, Object> view(Instant now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        if (!enabled) {
            out.put("state", "disabled");
            return out;
        }
        Snapshot snap = read();
        if (snap == null) {
            out.put("state", "not-installed");
            out.put("installCommands", INSTALL_COMMANDS);
            out.put("message", "The recovery heartbeat service has not written anything, so it is not installed (or has never "
                + "run). Nothing on the host is checking the components after a restart or watching them between restarts. "
                + "Install it once on the M920q (docs/MAINTENANCE.md, \"Recovery check\").");
            return out;
        }
        if (snap.unreadable()) {
            out.put("state", "silent");
            out.put("message", "The recovery service's state file is unreadable, so its report cannot be trusted.");
            out.put("installCommands", INSTALL_COMMANDS);
            return out;
        }
        Map<String, Object> s = snap.state();
        long age = Math.max(0, Duration.between(snap.writtenAt(), now).toSeconds());
        boolean stale = age > staleAfterSeconds;
        String status = text(s.get("status"), 20);
        List<Map<String, Object>> comps = components(s.get("components"), now);
        boolean anyDown = comps.stream().anyMatch(c -> "down".equals(c.get("status")));
        out.put("state", "stopped".equals(status) ? "stopped" : stale ? "silent" : anyDown ? "degraded" : "ok");
        out.put("writtenAt", snap.writtenAt().toString());
        out.put("ageSeconds", age);
        out.put("stale", stale);
        out.put("status", status);
        out.put("intervalSeconds", number(s.get("intervalSeconds")));
        out.put("missLimit", number(s.get("missLimit")));
        out.put("host", text(s.get("host"), 60));
        out.put("bootAt", text(s.get("bootAt"), 40));
        out.put("notify", subset(s.get("notify"), "ntfy", "lastResult", "lastOkAt"));
        out.put("deadman", subset(s.get("deadman"), "configured", "lastResult", "platform"));
        out.put("boot", boot(s.get("boot")));
        out.put("components", comps);
        if (stale && !"stopped".equals(status)) {
            out.put("message", "The recovery service has not updated its report for " + minutes(age) + " minutes. Either it has "
                + "stopped or it is hung; the component list below is out of date.");
        }
        return out;
    }

    /** Current conditions for the Critical banner and Status Checks. Same shape as every other ActiveAlertService source. */
    public List<ActiveAlert> activeAlerts(Instant now) {
        List<ActiveAlert> alerts = new ArrayList<>();
        if (!enabled) return alerts;
        Snapshot snap = read();
        if (snap == null) {
            alerts.add(alert("recovery:not-installed", "Recovery heartbeat service", "RECOVERY_NOT_INSTALLED", "WARN", null,
                "Recovery heartbeat service is not installed",
                "Nothing on the host checks the components after a restart or watches them between restarts. Install it once "
                    + "on the M920q: docs/MAINTENANCE.md, \"Recovery check\"."));
            return alerts;
        }
        if (snap.unreadable()) {
            alerts.add(alert("recovery:service-silent", "Recovery heartbeat service", "RECOVERY_SILENT", "CRITICAL", null,
                "Recovery service not reporting",
                "Its state file is unreadable, so the components are not being watched. Check the service on the host: "
                    + "systemctl status cabin-recovery-heartbeat."));
            return alerts;
        }
        Map<String, Object> s = snap.state();
        String status = text(s.get("status"), 20);
        long age = Math.max(0, Duration.between(snap.writtenAt(), now).toSeconds());
        if ("stopped".equals(status)) {
            alerts.add(alert("recovery:stopped", "Recovery heartbeat service", "RECOVERY_STOPPED", "CRITICAL", snap.writtenAt(),
                "Recovery heartbeat stopped on purpose",
                "The host service was stopped, so no component is being watched and the dead-man signal has ended. Start it: "
                    + "sudo systemctl start cabin-recovery-heartbeat."));
            return alerts;
        }
        if (age > staleAfterSeconds) {
            alerts.add(alert("recovery:service-silent", "Recovery heartbeat service", "RECOVERY_SILENT", "CRITICAL", snap.writtenAt(),
                "Recovery service not reporting",
                "No update from the host service for " + minutes(age) + " minutes (it writes every "
                    + number(s.get("intervalSeconds")) + " s). Components are not being watched: check systemctl status "
                    + "cabin-recovery-heartbeat on the M920q."));
            return alerts;
        }
        for (Map<String, Object> c : components(s.get("components"), now)) {
            if (!"down".equals(c.get("status"))) continue;
            String name = String.valueOf(c.get("name"));
            Instant since = instant(c.get("downSince"));
            alerts.add(alert("recovery:component:" + name, "Component " + name, "COMPONENT_DOWN", "CRITICAL", since,
                name + " is down",
                // Deliberately generic: this reaches /api/alerts/active, which the demo can read. The probe's own text
                // (addresses, peer names) is shown only on the administrator-only Monitoring view.
                "The host heartbeat has missed " + name + " for " + number(s.get("missLimit")) + " checks in a row. "
                    + "Administrators can see why under Monitoring, Recovery and heartbeats."));
        }
        return alerts;
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static ActiveAlert alert(String id, String sourceName, String condition, String severity, Instant evidenceAt,
                                     String title, String detail) {
        return new ActiveAlert(id, id.replace(':', '-'), sourceName, "cabin", condition, severity, evidenceAt, title, detail);
    }

    private List<Map<String, Object>> components(Object raw, Instant now) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (out.size() >= MAX_COMPONENTS) break;
            if (!(o instanceof Map<?, ?> m)) continue;
            String name = text(m.get("name"), 41);
            if (name == null || !NAME.matcher(name).matches()) continue;
            String status = "down".equals(m.get("status")) ? "down" : "ok";
            Instant lastOk = instant(m.get("lastOkAt"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("status", status);
            row.put("lastOkAt", lastOk == null ? null : lastOk.toString());
            row.put("ageSeconds", lastOk == null ? null : Math.max(0, Duration.between(lastOk, now).toSeconds()));
            row.put("misses", number(m.get("misses")));
            row.put("downSince", "down".equals(status) && instant(m.get("downSince")) != null ? instant(m.get("downSince")).toString() : null);
            row.put("detail", text(m.get("detail"), MAX_TEXT));
            out.add(row);
        }
        return out;
    }

    private Map<String, Object> boot(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("checkedAt", text(m.get("checkedAt"), 40));
        String sev = text(m.get("severity"), 10);
        out.put("severity", "critical".equals(sev) || "warn".equals(sev) ? sev : "info");
        out.put("uncleanShutdown", text(m.get("uncleanShutdown"), 10));
        out.put("recoveredMinutes", number(m.get("recoveredMinutes")));
        out.put("telemetryGapSeconds", number(m.get("telemetryGapSeconds")));
        out.put("summary", text(m.get("summary"), 900));
        List<Map<String, Object>> problems = new ArrayList<>();
        if (m.get("problems") instanceof List<?> list) {
            for (Object o : list) {
                if (problems.size() >= MAX_PROBLEMS) break;
                if (!(o instanceof Map<?, ?> p)) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", text(p.get("name"), 60));
                row.put("level", "critical".equals(p.get("level")) ? "critical" : "warn");
                row.put("detail", text(p.get("detail"), MAX_TEXT));
                problems.add(row);
            }
        }
        out.put("problems", problems);
        return out;
    }

    private static Map<String, Object> subset(Object raw, String... keys) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) return out;
        for (String k : keys) {
            Object v = m.get(k);
            out.put(k, v instanceof Boolean b ? b : text(v, 60));
        }
        return out;
    }

    private static String text(Object v, int max) {
        if (v == null) return null;
        String s = String.valueOf(v).replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static Long number(Object v) {
        return v instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    private static Instant instant(Object v) {
        try { return v == null ? null : Instant.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static long minutes(long seconds) {
        return Math.max(1, seconds / 60);
    }
}
