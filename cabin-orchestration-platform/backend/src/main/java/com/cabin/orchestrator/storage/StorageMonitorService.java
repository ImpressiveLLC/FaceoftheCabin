package com.cabin.orchestrator.storage;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.kafka.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * W-11: watches the M920q's disks and says so before they fill.
 *
 * Found 2026-10-10 sizing recording retention: the 916 GB rotational HDD
 * (/storage, the cold tier -- docs/STORAGE_ARCHITECTURE.md) had reached 94%
 * with 57 GB free and nothing had said a word; it surfaced only because
 * someone ran du. This service measures each configured mount every few
 * minutes, and when one crosses a threshold it publishes a CabinEvent, which
 * is persisted (so it shows in Rules & Alerts' 24-hour list, with SEE/THINK/ACT
 * text) and, when an ntfy topic is configured, pushed to the phone.
 *
 * Levels: WARN at {@code warnPercent} (80), CRITICAL at {@code criticalPercent}
 * (90). A mount drops back a level only after falling {@code clearMarginPercent}
 * below the line it crossed, so a disk hovering at 80 does not flap. While a
 * mount stays above a line the same alert repeats every {@code renotifyHours}.
 *
 * Severity of the HDD's critical alert depends on the host's automatic backup trim (W-11b, read through
 * {@link AutoTrimStatus}). While automatic trim is installed, switched on and alive, crossing the critical
 * line is not an emergency: the agent removes the oldest backup copies by itself and the level corrects.
 * That first alert is published as WARN (visible, no phone push) and says so. It is raised to CRITICAL, once,
 * if the agent is gone or switched off, or its last automatic run could not get the disk under its line, and
 * the repeat notice after {@code renotifyHours} is CRITICAL too, because the disk should have cleared by then.
 * Other mounts (the NVMe) have no automatic remedy and stay CRITICAL.
 *
 * This only reports. It never deletes anything: Nate's rules (2026-10-10) are
 * that BI telemetry is never removed without a warning first, and permanent
 * deletions are run by the owner. The automatic oldest-first purge W-11 first
 * described is deferred (W-11b); Frigate already expires its own recordings by
 * its retention settings.
 *
 * The backend container sees the HDD through the archive mount
 * (/storage/archives/cabin_event -> /app/archives/cabin_event), so statvfs on
 * that path reports the whole /storage filesystem; "/" is the container's
 * overlay on the NVMe, the hot tier where Postgres lives.
 */
@Service
public class StorageMonitorService {

    private static final Logger log = LoggerFactory.getLogger(StorageMonitorService.class);

    public static final String EVENT_THRESHOLD = "STORAGE_THRESHOLD";
    public static final String EVENT_CLEARED = "STORAGE_CLEARED";
    private static final double GB = 1024.0 * 1024.0 * 1024.0;

    public enum Level { NORMAL, WARN, CRITICAL }

    /** Filesystem figures for one mount, in bytes. */
    public record MountUsage(String label, String path, long totalBytes, long freeBytes, long availBytes) {
        /** df's own definition: used / (used + available), rounded up, so it matches what an operator sees. */
        public int usedPercent() {
            long used = Math.max(0, totalBytes - freeBytes);
            long denom = used + availBytes;
            return denom <= 0 ? 0 : (int) Math.ceil(used * 100.0 / denom);
        }
    }

    /** Seam for tests: reads a mount's usage, or nothing if the path is not there. */
    public interface DiskProbe {
        Optional<MountUsage> probe(String label, String path);
    }

    static final DiskProbe FILE_PROBE = (label, path) -> {
        File f = new File(path);
        if (!f.exists()) return Optional.empty();
        long total = f.getTotalSpace();
        if (total <= 0) return Optional.empty();
        return Optional.of(new MountUsage(label, path, total, f.getFreeSpace(), f.getUsableSpace()));
    };

    private final EventPublisher eventPublisher;
    private final CabinEventService eventService;
    private final DiskProbe probe;
    private final boolean enabled;
    private final Map<String, String> mounts;
    private final int warnPercent;
    private final int criticalPercent;
    private final int clearMarginPercent;
    private final Duration renotify;
    private final Supplier<AutoTrimStatus> autoTrim;

    private final Map<String, Level> levels = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastNotified = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seeded = new ConcurrentHashMap<>();
    /** Mounts whose critical alert went out as WARN because automatic trim was expected to clear it. */
    private final Set<String> softened = ConcurrentHashMap.newKeySet();

    @Autowired
    public StorageMonitorService(
            EventPublisher eventPublisher,
            CabinEventService eventService,
            @Value("${cabin.storage.monitor.enabled:true}") boolean enabled,
            @Value("${cabin.storage.monitor.mounts:storage-hdd=/app/archives/cabin_event,root-nvme=/}") String mountsSpec,
            @Value("${cabin.storage.monitor.warnPercent:60}") int warnPercent,
            @Value("${cabin.storage.monitor.criticalPercent:75}") int criticalPercent,
            @Value("${cabin.storage.monitor.clearMarginPercent:3}") int clearMarginPercent,
            @Value("${cabin.storage.monitor.renotifyHours:24}") int renotifyHours,
            @Value("${cabin.storage.trimSpoolDir:/app/archives/cabin_event/.trim}") String trimSpoolDir) {
        this(eventPublisher, eventService, FILE_PROBE, enabled, parseMounts(mountsSpec),
            warnPercent, criticalPercent, clearMarginPercent, renotifyHours,
            () -> AutoTrimStatus.read(Path.of(trimSpoolDir), Instant.now()));
    }

    StorageMonitorService(EventPublisher eventPublisher, CabinEventService eventService, DiskProbe probe,
                          boolean enabled, Map<String, String> mounts,
                          int warnPercent, int criticalPercent, int clearMarginPercent, int renotifyHours) {
        this(eventPublisher, eventService, probe, enabled, mounts, warnPercent, criticalPercent,
            clearMarginPercent, renotifyHours, () -> AutoTrimStatus.ABSENT);
    }

    StorageMonitorService(EventPublisher eventPublisher, CabinEventService eventService, DiskProbe probe,
                          boolean enabled, Map<String, String> mounts,
                          int warnPercent, int criticalPercent, int clearMarginPercent, int renotifyHours,
                          Supplier<AutoTrimStatus> autoTrim) {
        if (warnPercent >= criticalPercent) {
            throw new IllegalArgumentException("warnPercent must be below criticalPercent");
        }
        this.eventPublisher = eventPublisher;
        this.eventService = eventService;
        this.probe = probe;
        this.enabled = enabled;
        this.mounts = mounts;
        this.warnPercent = warnPercent;
        this.criticalPercent = criticalPercent;
        this.clearMarginPercent = clearMarginPercent;
        this.renotify = Duration.ofHours(renotifyHours);
        this.autoTrim = autoTrim;
    }

    static Map<String, String> parseMounts(String spec) {
        Map<String, String> out = new LinkedHashMap<>();
        if (spec == null) return out;
        for (String pair : spec.split(",")) {
            int idx = pair.indexOf('=');
            if (idx <= 0 || idx == pair.length() - 1) continue;
            out.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
        }
        return out;
    }

    @Scheduled(fixedDelayString = "${cabin.storage.monitor.intervalMs:300000}",
               initialDelayString = "${cabin.storage.monitor.initialDelayMs:60000}")
    public void check() {
        if (!enabled) return;
        try {
            evaluate(Instant.now());
        } catch (Exception e) {
            // Monitoring must never take the app down; a missed pass just means the next one catches up.
            log.warn("Storage check failed: {}", e.getMessage());
        }
    }

    /** One pass over every mount at the given time. Package-visible so tests drive the clock directly. */
    void evaluate(Instant now) {
        for (Map.Entry<String, String> m : mounts.entrySet()) {
            String label = m.getKey();
            Optional<MountUsage> usage = probe.probe(label, m.getValue());
            if (usage.isEmpty()) {
                log.debug("Storage mount {} ({}) not readable; skipped", label, m.getValue());
                continue;
            }
            int pct = usage.get().usedPercent();
            seedFromHistory(label, now);
            Level current = levels.getOrDefault(label, Level.NORMAL);
            Level next = nextLevel(current, pct);

            if (next != current) {
                levels.put(label, next);
                lastNotified.put(label, now);
                softened.remove(label);
                publish(usage.get(), next, now, false);
            } else if (next != Level.NORMAL) {
                Instant last = lastNotified.get(label);
                if (last == null || Duration.between(last, now).compareTo(renotify) >= 0) {
                    lastNotified.put(label, now);
                    publish(usage.get(), next, now, true);
                } else if (next == Level.CRITICAL && softened.contains(label) && !autoTrimExpectedToClear(label)) {
                    // The first notice promised automatic trim would handle it. It cannot (agent gone, switched
                    // off, or at its floor), so say so now, once, and push.
                    lastNotified.put(label, now);
                    publish(usage.get(), next, now, false);
                }
            }
        }
    }

    /** Hysteresis: climb at the line, fall only {@code clearMarginPercent} below it. */
    Level nextLevel(Level current, int pct) {
        if (pct >= criticalPercent) return Level.CRITICAL;
        if (current == Level.CRITICAL && pct >= criticalPercent - clearMarginPercent) return Level.CRITICAL;
        if (pct >= warnPercent) return Level.WARN;
        if (current != Level.NORMAL && pct >= warnPercent - clearMarginPercent) return Level.WARN;
        return Level.NORMAL;
    }

    /**
     * After a restart (every deploy) the in-memory state is empty, which would re-send an alert for a
     * disk that is still full. Reading the last event for this mount restores the level and the time of
     * the last notice, so a deploy does not re-page and the repeat clock keeps counting.
     */
    private void seedFromHistory(String label, Instant now) {
        if (seeded.putIfAbsent(label, Boolean.TRUE) != null) return;
        try {
            List<CabinEvent> last = eventService.recent(sourceId(label), 1, now.minus(renotify.multipliedBy(2)));
            if (last == null || last.isEmpty()) return;
            CabinEvent e = last.get(0);
            Object lvl = e.payload() == null ? null : e.payload().get("level");
            if (lvl == null) return;
            levels.put(label, Level.valueOf(String.valueOf(lvl)));
            lastNotified.put(label, e.timestamp());
            if (Boolean.TRUE.equals(e.payload().get("softened"))) softened.add(label);
        } catch (Exception ex) {
            log.debug("Could not restore storage state for {}: {}", label, ex.getMessage());
        }
    }

    /** Only the cold-tier HDD has an automatic remedy: the host agent that trims old backups. */
    private static boolean hasAutoRemedy(String label) {
        return label.startsWith("storage") || label.contains("hdd");
    }

    private boolean autoTrimExpectedToClear(String label) {
        if (!hasAutoRemedy(label)) return false;
        AutoTrimStatus s = autoTrim.get();
        return s.healthy() && !s.stuck();
    }

    private void publish(MountUsage u, Level level, Instant now, boolean reminder) {
        int pct = u.usedPercent();
        double freeGb = u.availBytes() / GB;
        double totalGb = u.totalBytes() / GB;
        boolean cleared = level == Level.NORMAL;
        int line = level == Level.CRITICAL ? criticalPercent : warnPercent;

        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ruleId", EVENT_THRESHOLD);
        p.put("mount", u.label());
        p.put("path", u.path());
        p.put("level", level.name());
        p.put("usedPercent", pct);
        p.put("freeBytes", u.availBytes());
        p.put("totalBytes", u.totalBytes());
        p.put("warnPercent", warnPercent);
        p.put("criticalPercent", criticalPercent);
        p.put("reminder", reminder);
        String see = String.format("%s is %d%% full (%.0f GB free of %.0f GB)", u.label(), pct, freeGb, totalGb);
        p.put("see", see);
        if (cleared) {
            p.put("think", String.format("Back below the %d%% line", warnPercent));
            p.put("act", "Nothing to do");
        } else {
            p.put("think", String.format("%s the %d%% %s line%s", reminder ? "Still above" : "Crossed", line,
                level == Level.CRITICAL ? "critical" : "warning", reminder ? " (repeat notice)" : ""));
            boolean soften = level == Level.CRITICAL && !reminder && autoTrimExpectedToClear(u.label());
            if (soften) softened.add(u.label()); else softened.remove(u.label());
            p.put("softened", soften);
            p.put("act", actionFor(u.label(), level, soften));
            p.put("notify", !soften);
            p.put("title", "Cabin storage: " + u.label() + " " + pct + "% full");
            p.put("message", see + ". " + p.get("act"));
        }
        boolean wasSoftened = Boolean.TRUE.equals(p.get("softened"));
        String severity = cleared ? "INFO" : (level == Level.CRITICAL && !wasSoftened ? "CRITICAL" : "WARN");
        eventPublisher.publish(new CabinEvent(UUID.randomUUID().toString(), sourceId(u.label()),
            cleared ? EVENT_CLEARED : EVENT_THRESHOLD, severity, now, p));
        log.info("Storage {}: {} -> {}{}", u.label(), pct + "%", level, reminder ? " (repeat)" : "");
    }

    /**
     * Plain-language next step; never "delete X" automatically, see class doc. For the HDD at the critical
     * line the text depends on whether automatic trim is working, so the reader is not told to panic about
     * something the host will fix, nor told to relax when nothing will.
     */
    private String actionFor(String label, Level level, boolean softened) {
        if (!hasAutoRemedy(label)) {
            return "Check Docker usage (docker system df) and old images or build cache; Postgres lives here, so do not delete volumes.";
        }
        String manual = "Trim old nightly backups first: choose how many to keep, see how much it frees, then press Go (below). "
            + "Then review recording retention. BI telemetry is never removed without a warning (docs/STORAGE_ARCHITECTURE.md).";
        if (level != Level.CRITICAL) return manual;
        AutoTrimStatus s = autoTrim.get();
        if (!s.installed()) {
            return "Automatic trim is not installed on the host, so nothing will clear this on its own and it needs you. " + manual
                + " To turn automatic trim on, install the host trim service (docs/STORAGE_ARCHITECTURE.md, \"Trim control\").";
        }
        if (!s.enabled()) {
            return "Automatic trim is installed but switched off (TRIM_AUTO=0 on the host), so nothing will clear this on its own and it needs you. " + manual;
        }
        if (s.planStale()) {
            return "The host trim service has stopped updating, so automatic trim may not be running and this needs you. " + manual;
        }
        String how = String.format("it removes the oldest backup copies until the disk is down to %d%%, keeping at least %d per folder",
            s.downTo(), s.floorKeep());
        if (s.stuck()) {
            return String.format("Automatic trim ran but could not get the disk under its line (%s; every folder is already at its minimum, or the run failed). "
                + "This needs you. ", how) + manual;
        }
        if (softened) {
            return String.format("Not a true emergency: automatic trim is on. While the disk is at %d%% or more, %s, and the level corrects itself. "
                + "You will be pushed again only if the disk is still this full after a day or automatic trim cannot free enough. "
                + "You can also trim now: ", s.aboveFull(), how) + manual;
        }
        return String.format("Automatic trim is on (%s) but the disk is still this full a day later, so it needs you. ", how) + manual;
    }

    static String sourceId(String label) {
        return "storage-" + label;
    }

    /** Current readings for GET /api/system/storage. */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, String> m : mounts.entrySet()) {
            Optional<MountUsage> usage = probe.probe(m.getKey(), m.getValue());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("mount", m.getKey());
            if (usage.isEmpty()) {
                row.put("available", false);
            } else {
                MountUsage u = usage.get();
                row.put("available", true);
                row.put("usedPercent", u.usedPercent());
                row.put("freeGb", Math.round(u.availBytes() / GB * 10) / 10.0);
                row.put("totalGb", Math.round(u.totalBytes() / GB * 10) / 10.0);
                row.put("level", levels.getOrDefault(m.getKey(), nextLevel(Level.NORMAL, u.usedPercent())).name());
            }
            row.put("warnPercent", warnPercent);
            row.put("criticalPercent", criticalPercent);
            out.add(row);
        }
        return out;
    }
}
