package com.cabin.orchestrator.alerts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only alert history (W-56). Holds only what no other table does:
 * device-condition RAISED/RESOLVED transitions (ActiveAlertService is
 * stateless by design) and every acknowledgment action. Automation alerts
 * are NOT copied here -- they already live in cabin_event.
 *
 * Rows are never updated or deleted by application code. Same idempotent
 * CREATE TABLE IF NOT EXISTS pattern as JdbcAlertAcknowledgmentStore.
 */
@Repository
public class JdbcAlertHistoryStore {

    private final JdbcTemplate jdbc;

    public JdbcAlertHistoryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS alert_history (
              id             BIGSERIAL PRIMARY KEY,
              occurred_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
              alert_key      TEXT NOT NULL,
              kind           TEXT NOT NULL,
              device_id      TEXT,
              condition      TEXT,
              rule_id        TEXT,
              severity       TEXT,
              action         TEXT NOT NULL,
              actor          TEXT,
              snoozed_until  TIMESTAMPTZ,
              title          TEXT
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS alert_history_time_idx ON alert_history (occurred_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS alert_history_key_idx ON alert_history (alert_key, occurred_at DESC)");
    }

    public void append(AlertHistoryEntry e) {
        jdbc.update("""
            INSERT INTO alert_history
              (occurred_at, alert_key, kind, device_id, condition, rule_id, severity, action, actor, snoozed_until, title)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
            Timestamp.from(e.occurredAt()), e.alertKey(), e.kind(), e.deviceId(), e.condition(), e.ruleId(),
            e.severity(), e.action(), e.actor(),
            e.snoozedUntil() == null ? null : Timestamp.from(e.snoozedUntil()), e.title());
    }

    /** Newest first. deviceId is nullable (null = every device). */
    public List<AlertHistoryEntry> since(Instant since, String deviceId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM alert_history WHERE occurred_at >= ?");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(since));
        if (deviceId != null && !deviceId.isBlank()) {
            sql.append(" AND device_id = ?");
            args.add(deviceId);
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), (rs, i) -> new AlertHistoryEntry(
            "h:" + rs.getLong("id"),
            rs.getTimestamp("occurred_at").toInstant(),
            rs.getString("alert_key"), rs.getString("kind"), rs.getString("device_id"),
            rs.getString("condition"), rs.getString("rule_id"), rs.getString("severity"),
            rs.getString("action"), rs.getString("actor"),
            rs.getTimestamp("snoozed_until") == null ? null : rs.getTimestamp("snoozed_until").toInstant(),
            rs.getString("title")), args.toArray());
    }

    /** W-57: rows grouped by (alert_key, action) since a time; deviceId is nullable (null = every device). */
    public List<AlertHistoryCount> countsSince(Instant since, String deviceId) {
        StringBuilder sql = new StringBuilder("""
            SELECT alert_key, kind, device_id, condition, action, COUNT(*) AS n,
                   MIN(occurred_at) AS first_at, MAX(occurred_at) AS last_at
            FROM alert_history WHERE occurred_at >= ?""");
        List<Object> args = new ArrayList<>();
        args.add(Timestamp.from(since));
        if (deviceId != null && !deviceId.isBlank()) {
            sql.append(" AND device_id = ?");
            args.add(deviceId);
        }
        sql.append(" GROUP BY alert_key, kind, device_id, condition, action");
        return jdbc.query(sql.toString(), (rs, i) -> new AlertHistoryCount(
            rs.getString("alert_key"), rs.getString("kind"), rs.getString("device_id"), rs.getString("condition"),
            rs.getString("action"), rs.getLong("n"),
            rs.getTimestamp("first_at").toInstant(), rs.getTimestamp("last_at").toInstant()), args.toArray());
    }

    /** alertKey -> latest of its RAISED/RESOLVED rows' action. A key is "open" iff its value is RAISED. */
    public Map<String, String> latestTransitionByKey() {
        Map<String, String> latest = new HashMap<>();
        jdbc.query("""
            SELECT DISTINCT ON (alert_key) alert_key, action
            FROM alert_history WHERE action IN ('RAISED','RESOLVED')
            ORDER BY alert_key, occurred_at DESC, id DESC""",
            rs -> { latest.put(rs.getString("alert_key"), rs.getString("action")); });
        return latest;
    }
}
