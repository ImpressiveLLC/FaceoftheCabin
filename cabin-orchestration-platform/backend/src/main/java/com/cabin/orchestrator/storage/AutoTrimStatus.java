package com.cabin.orchestrator.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * What the disk alert and the Trim control need to know about the host's automatic backup trim (W-11b),
 * read from the spool folder the host agent writes (plan.json and result.json, see StorageTrimService).
 *
 * "Healthy" means the agent has written a plan recently and automatic trimming is switched on. It does not
 * mean the disk will clear: {@code stuck} says the last automatic run could not get the disk under its line
 * (every folder already at its minimum number of copies) or failed.
 */
public record AutoTrimStatus(boolean installed, boolean enabled, boolean planStale, boolean stuck,
                             int aboveFull, int downTo, int floorKeep) {

    /** A plan older than this means the agent has stopped refreshing it (it refreshes every 6 hours). */
    static final Duration STALE_AFTER = Duration.ofHours(12);
    /** An automatic run older than this no longer says anything about the disk as it is now. */
    static final Duration RESULT_RELEVANT_FOR = Duration.ofHours(24);

    static final AutoTrimStatus ABSENT = new AutoTrimStatus(false, false, false, false, 0, 0, 0);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Automatic trim is installed, switched on and the agent is still alive. */
    public boolean healthy() {
        return installed && enabled && !planStale;
    }

    /** Reads the spool folder at the given time. A missing or unreadable plan means not installed. */
    static AutoTrimStatus read(Path spoolDir, Instant now) {
        Map<String, Object> plan = readJson(spoolDir.resolve("plan.json"));
        if (plan == null) return ABSENT;
        Instant plannedAt = instant(plan.get("plannedAt"));
        boolean stale = plannedAt == null || Duration.between(plannedAt, now).compareTo(STALE_AFTER) > 0;
        boolean enabled = false;
        int above = 0, to = 0, floor = 0;
        if (plan.get("auto") instanceof Map<?, ?> a) {
            enabled = Boolean.TRUE.equals(a.get("enabled"));
            above = intOf(a.get("aboveFull"));
            to = intOf(a.get("downTo"));
            floor = intOf(a.get("floorKeep"));
        }
        boolean stuck = false;
        Map<String, Object> result = readJson(spoolDir.resolve("result.json"));
        if (result != null && Boolean.TRUE.equals(result.get("auto"))) {
            Instant at = instant(result.get("finishedAt"));
            boolean recent = at != null && Duration.between(at, now).compareTo(RESULT_RELEVANT_FOR) <= 0;
            stuck = recent && (!Boolean.TRUE.equals(result.get("ok")) || Boolean.FALSE.equals(result.get("reachedTarget")));
        }
        return new AutoTrimStatus(true, enabled, stale, stuck, above, to, floor);
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Instant instant(Object v) {
        try { return v == null ? null : Instant.parse(String.valueOf(v)); } catch (Exception e) { return null; }
    }

    private static Map<String, Object> readJson(Path p) {
        try {
            if (!Files.isRegularFile(p) || Files.isSymbolicLink(p) || Files.size(p) > 2_000_000) return null;
            return MAPPER.readValue(Files.readString(p, StandardCharsets.UTF_8), new TypeReference<Map<String, Object>>() {});
        } catch (IOException e) {
            return null;
        }
    }
}
