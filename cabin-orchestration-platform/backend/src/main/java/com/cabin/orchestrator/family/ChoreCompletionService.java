package com.cabin.orchestrator.family;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors family-hub.html's old localStorage COMP_KEY shape exactly:
 * completion["<dayKey>_<kidId>"][choreId] = true/false. Fetching the whole
 * map in one call (instead of per-day queries) matches how the frontend
 * already reads it — recentHomeDays() scans up to 30 days back locally, so
 * one bulk fetch avoids 30 round-trips.
 */
@Service
public class ChoreCompletionService {

    private final JdbcTemplate jdbc;

    public ChoreCompletionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chore_completion (
              day_key  VARCHAR(10)  NOT NULL,
              kid_id   VARCHAR(128) NOT NULL,
              chore_id VARCHAR(128) NOT NULL,
              done     BOOLEAN NOT NULL DEFAULT true,
              updated_at BIGINT NOT NULL,
              PRIMARY KEY (day_key, kid_id, chore_id)
            )""");
    }

    /** { "<dayKey>_<kidId>": { choreId: true, ... }, ... } — only rows where done=true are stored. */
    public Map<String, Map<String, Boolean>> all() {
        Map<String, Map<String, Boolean>> result = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM chore_completion WHERE done = true");
        for (Map<String, Object> row : rows) {
            String key = row.get("day_key") + "_" + row.get("kid_id");
            result.computeIfAbsent(key, k -> new LinkedHashMap<>()).put((String) row.get("chore_id"), true);
        }
        return result;
    }

    /** Flips completion and returns the new state. */
    public boolean toggle(String dayKey, String kidId, String choreId) {
        List<Map<String, Object>> existing = jdbc.queryForList(
            "SELECT done FROM chore_completion WHERE day_key=? AND kid_id=? AND chore_id=?",
            dayKey, kidId, choreId);
        boolean newDone = existing.isEmpty() || !((Boolean) existing.get(0).get("done"));
        jdbc.update("""
            INSERT INTO chore_completion (day_key, kid_id, chore_id, done, updated_at)
            VALUES (?,?,?,?,?)
            ON CONFLICT (day_key, kid_id, chore_id) DO UPDATE SET
              done = EXCLUDED.done, updated_at = EXCLUDED.updated_at""",
            dayKey, kidId, choreId, newDone, System.currentTimeMillis());
        return newDone;
    }
}
