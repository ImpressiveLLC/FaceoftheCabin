package com.cabin.orchestrator.alerts;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

/**
 * Persists alert acknowledgments (ignore/snooze). Same idempotent
 * CREATE TABLE IF NOT EXISTS + upsert pattern as JdbcWorkflowRuleStore --
 * see that class for the precedent this follows.
 */
@Repository
public class JdbcAlertAcknowledgmentStore {

    private final JdbcTemplate jdbc;

    public JdbcAlertAcknowledgmentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS alert_acknowledgment (
              alert_key        TEXT PRIMARY KEY,
              mode             TEXT NOT NULL,
              snoozed_until    TIMESTAMPTZ,
              acknowledged_by  TEXT,
              acknowledged_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""");
    }

    public void save(AlertAcknowledgment ack) {
        jdbc.update("""
            INSERT INTO alert_acknowledgment (alert_key, mode, snoozed_until, acknowledged_by, acknowledged_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (alert_key) DO UPDATE SET
              mode = EXCLUDED.mode, snoozed_until = EXCLUDED.snoozed_until,
              acknowledged_by = EXCLUDED.acknowledged_by, acknowledged_at = EXCLUDED.acknowledged_at
            """,
            ack.alertKey(), ack.mode(),
            ack.snoozedUntil() == null ? null : Timestamp.from(ack.snoozedUntil()),
            ack.acknowledgedBy(), Timestamp.from(ack.acknowledgedAt()));
    }

    public void clear(String alertKey) {
        jdbc.update("DELETE FROM alert_acknowledgment WHERE alert_key = ?", alertKey);
    }

    /**
     * Active = IGNORED (no expiry) or SNOOZED with snoozed_until still in
     * the future. An expired SNOOZED row is deleted here, not just
     * filtered out of the result -- the table stays a live "currently
     * suppressed" set, not a growing history of every acknowledgment ever
     * made (that's a real, separate ask -- see MAINTENANCE.md's Status
     * Checks section, "show me history" is explicitly not this table).
     */
    public List<AlertAcknowledgment> activeAcknowledgments() {
        jdbc.update("DELETE FROM alert_acknowledgment WHERE mode = 'SNOOZED' AND snoozed_until < now()");
        return jdbc.query(
            "SELECT alert_key, mode, snoozed_until, acknowledged_by, acknowledged_at FROM alert_acknowledgment",
            (rs, i) -> new AlertAcknowledgment(
                rs.getString("alert_key"),
                rs.getString("mode"),
                rs.getTimestamp("snoozed_until") == null ? null : rs.getTimestamp("snoozed_until").toInstant(),
                rs.getString("acknowledged_by"),
                rs.getTimestamp("acknowledged_at").toInstant()));
    }
}
