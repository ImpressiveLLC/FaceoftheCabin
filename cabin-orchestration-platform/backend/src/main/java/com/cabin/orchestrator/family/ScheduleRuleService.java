package com.cabin.orchestrator.family;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Same JdbcTemplate + CREATE TABLE IF NOT EXISTS self-migration pattern as
 * FamilyProfileService/ChoreCompletionService -- table creation runs
 * directly in the constructor (not @PostConstruct) so a plain
 * `new ScheduleRuleService(jdbc)` in a Testcontainers test works, same
 * reasoning FamilyProfileService's own constructor comment documents.
 *
 * Was previously localStorage-only (family-hub.html's SCHEDULE_RULES) --
 * see docs/ontology.yaml's parenting_schedule_rule_version entity, whose
 * own notes already flagged this as the planned direction. Seeds the two
 * rule versions that were already hardcoded as DEFAULT_SCHEDULE_RULES in
 * family-hub.html, so a fresh deploy matches what was already live rather
 * than appearing to "lose" the family's real schedule.
 */
@Service
public class ScheduleRuleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleRuleService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public ScheduleRuleService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS schedule_rule (
              id             VARCHAR(128) PRIMARY KEY,
              effective_from VARCHAR(10) NOT NULL,
              anchor         VARCHAR(10) NOT NULL,
              day_owners     JSONB NOT NULL,
              label          VARCHAR(256),
              created_at     BIGINT NOT NULL,
              created_by     VARCHAR(128)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_schedule_rule_effective ON schedule_rule (effective_from)");
        seedIfEmpty();
    }

    // Matches DEFAULT_SCHEDULE_RULES in family-hub.html exactly (ids, dates,
    // dayOwners, labels, createdAt/createdBy) so this deploy's first sync
    // doesn't appear to diverge from what every existing device already has.
    private void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM schedule_rule", Integer.class);
        if (count != null && count > 0) return;

        insertRow(new ScheduleRule(
            "rule-2026-03-13", "2026-03-13", "2026-03-13",
            homeDaysToOwners(List.of(0, 1, 4, 5, 8, 9, 12, 13)),
            "Original 2026 custody schedule",
            Instant.parse("2026-03-13T12:00:00.000Z").toEpochMilli(), "seed"));
        insertRow(new ScheduleRule(
            "rule-2026-07-27", "2026-07-27", "2026-07-27",
            homeDaysToOwners(List.of(2, 3, 4, 5, 9, 10)),
            "50/50 schedule agreed 2026-07-27",
            Instant.parse("2026-07-29T12:00:00.000Z").toEpochMilli(), "seed"));
    }

    private static Map<Integer, String> homeDaysToOwners(List<Integer> dadDays) {
        Map<Integer, String> owners = new LinkedHashMap<>();
        for (int i = 0; i < 14; i++) owners.put(i, dadDays.contains(i) ? "dad" : "mom");
        return owners;
    }

    public List<ScheduleRule> list() {
        return jdbc.query("SELECT * FROM schedule_rule ORDER BY effective_from", ROW_MAPPER);
    }

    public ScheduleRule latest() {
        List<ScheduleRule> rows = jdbc.query(
            "SELECT * FROM schedule_rule ORDER BY effective_from DESC LIMIT 1", ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Same amend-vs-append decision family-hub.html's saveSchedCfg() already
     * makes client-side: the same effectiveFrom as the current latest rule
     * amends it in place (anchor/dayOwners only -- id/createdAt/createdBy/
     * label untouched); a different effectiveFrom appends a brand-new
     * version and leaves every earlier one untouched, so historical days
     * keep resolving under whatever was actually in effect then. Centralized
     * here so any future client gets the same correctness for free instead
     * of re-implementing it.
     */
    public ScheduleRule save(ScheduleRule input, String actorId) {
        ScheduleRule latest = latest();
        if (latest != null && latest.effectiveFrom().equals(input.effectiveFrom())) {
            jdbc.update("UPDATE schedule_rule SET anchor = ?, day_owners = ?::jsonb WHERE id = ?",
                input.anchor(), toJson(input.dayOwners()), latest.id());
            return byId(latest.id());
        }
        String id = input.id() != null ? input.id() : "rule-" + input.effectiveFrom() + "-" + System.currentTimeMillis();
        long createdAt = System.currentTimeMillis();
        String label = input.label() != null ? input.label() : "";
        insertRow(new ScheduleRule(id, input.effectiveFrom(), input.anchor(), input.dayOwners(), label, createdAt, actorId));
        return byId(id);
    }

    private void insertRow(ScheduleRule r) {
        jdbc.update("""
            INSERT INTO schedule_rule (id, effective_from, anchor, day_owners, label, created_at, created_by)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
            """,
            r.id(), r.effectiveFrom(), r.anchor(), toJson(r.dayOwners()), r.label(), r.createdAt(), r.createdBy());
    }

    private ScheduleRule byId(String id) {
        List<ScheduleRule> rows = jdbc.query("SELECT * FROM schedule_rule WHERE id = ?", ROW_MAPPER, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String toJson(Map<Integer, String> dayOwners) {
        try {
            return mapper.writeValueAsString(dayOwners == null ? Map.of() : dayOwners);
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialize dayOwners", e);
        }
    }

    private Map<Integer, String> fromJson(String json) {
        try {
            Map<String, String> raw = mapper.readValue(json, new TypeReference<Map<String, String>>() {});
            Map<Integer, String> owners = new LinkedHashMap<>();
            raw.forEach((k, v) -> owners.put(Integer.valueOf(k), v));
            return owners;
        } catch (Exception e) {
            log.warn("Could not parse schedule_rule.day_owners JSON, treating as empty: {}", e.getMessage());
            return Map.of();
        }
    }

    // rs.getString() on a JSONB column returns the raw JSON text directly --
    // same proven pattern JdbcWorkflowRuleStore already relies on for its
    // own JSONB column, deliberately NOT queryForList()'s generic
    // Map<String,Object> row shape (untested against a JSONB column in this
    // codebase; PGobject's toString() likely also works, but there's no
    // existing precedent to confirm it, so this sticks to what's proven).
    private final RowMapper<ScheduleRule> ROW_MAPPER = (rs, rowNum) -> new ScheduleRule(
        rs.getString("id"),
        rs.getString("effective_from"),
        rs.getString("anchor"),
        fromJson(rs.getString("day_owners")),
        rs.getString("label"),
        rs.getLong("created_at"),
        rs.getString("created_by"));
}
