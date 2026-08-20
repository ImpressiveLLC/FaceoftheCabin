package com.cabin.orchestrator.family;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Same constructor-based self-migration pattern as ScheduleRuleService/
 * FamilyProfileService. Unlike schedule rules, holidays ship with no seed
 * data -- family-hub.html never had a DEFAULT_HOLIDAYS array, HOLIDAYS
 * always started as an empty list.
 *
 * DELETE is a hard delete, not a soft one -- unlike FamilyProfile, no other
 * record references a holiday by id, so there's no historical-authorship
 * reason to keep a removed row around.
 */
@Service
public class HolidayService {

    private final JdbcTemplate jdbc;

    public HolidayService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS holiday (
              id         VARCHAR(128) PRIMARY KEY,
              date       VARCHAR(10) NOT NULL,
              name       VARCHAR(256) NOT NULL,
              owner      VARCHAR(8) NOT NULL,
              created_at BIGINT NOT NULL,
              created_by VARCHAR(128)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_holiday_date ON holiday (date)");
    }

    public List<Holiday> list() {
        return jdbc.queryForList("SELECT * FROM holiday ORDER BY date")
            .stream().map(this::fromRow).toList();
    }

    public Holiday byId(String id) {
        List<Holiday> rows = jdbc.queryForList("SELECT * FROM holiday WHERE id = ?", id)
            .stream().map(this::fromRow).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Holiday create(Holiday h, String actorId) {
        String id = h.id() != null ? h.id() : "holiday-" + System.currentTimeMillis();
        long now = System.currentTimeMillis();
        jdbc.update("""
            INSERT INTO holiday (id, date, name, owner, created_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              date = EXCLUDED.date, name = EXCLUDED.name, owner = EXCLUDED.owner
            """,
            id, h.date(), h.name(), h.owner(), now, actorId);
        return byId(id);
    }

    /** Partial update -- only non-null fields on the incoming record are applied. */
    public Holiday update(String id, Holiday patch, String actorId) {
        Holiday existing = byId(id);
        if (existing == null) return null;
        Holiday merged = new Holiday(
            id,
            patch.date() != null ? patch.date() : existing.date(),
            patch.name() != null ? patch.name() : existing.name(),
            patch.owner() != null ? patch.owner() : existing.owner(),
            existing.createdAt(),
            actorId != null ? actorId : existing.createdBy());
        jdbc.update("UPDATE holiday SET date=?, name=?, owner=?, created_by=? WHERE id = ?",
            merged.date(), merged.name(), merged.owner(), merged.createdBy(), id);
        return merged;
    }

    public boolean delete(String id) {
        return jdbc.update("DELETE FROM holiday WHERE id = ?", id) > 0;
    }

    private Holiday fromRow(Map<String, Object> row) {
        return new Holiday(
            (String) row.get("id"),
            (String) row.get("date"),
            (String) row.get("name"),
            (String) row.get("owner"),
            ((Number) row.get("created_at")).longValue(),
            (String) row.get("created_by"));
    }
}
