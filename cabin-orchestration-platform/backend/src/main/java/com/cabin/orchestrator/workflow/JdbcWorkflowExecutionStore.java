package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists each firing of a WorkflowRule. Same pattern as JdbcWorkflowRuleStore. */
@Repository
public class JdbcWorkflowExecutionStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowExecutionStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcWorkflowExecutionStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS workflow_execution (
              execution_id           TEXT PRIMARY KEY,
              workflow_id            TEXT REFERENCES workflow_rule(workflow_id),
              triggered_by_event_id  TEXT,
              fired_at               TIMESTAMPTZ NOT NULL,
              cleared_at             TIMESTAMPTZ,
              cleared_by             TEXT,
              action_results         JSONB NOT NULL,
              last_viewed_at         TIMESTAMPTZ
            )""");
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS workflow_execution_workflow_idx
            ON workflow_execution (workflow_id, fired_at DESC)""");
    }

    public void save(WorkflowExecution execution) {
        jdbc.update("""
            INSERT INTO workflow_execution (execution_id, workflow_id, triggered_by_event_id, fired_at,
              cleared_at, cleared_by, action_results, last_viewed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
            ON CONFLICT (execution_id) DO UPDATE SET
              cleared_at = EXCLUDED.cleared_at, cleared_by = EXCLUDED.cleared_by,
              action_results = EXCLUDED.action_results, last_viewed_at = EXCLUDED.last_viewed_at
            """,
            execution.executionId(), execution.workflowId(), execution.triggeredByEventId(),
            Timestamp.from(execution.firedAt()),
            execution.clearedAt() == null ? null : Timestamp.from(execution.clearedAt()),
            execution.clearedBy(), toJson(execution.actionResults()),
            execution.lastViewedAt() == null ? null : Timestamp.from(execution.lastViewedAt()));
    }

    public List<WorkflowExecution> recentFor(String workflowId, int limit) {
        return jdbc.query("""
            SELECT * FROM workflow_execution WHERE workflow_id = ? ORDER BY fired_at DESC LIMIT ?
            """, (rs, rowNum) -> map(rs), workflowId, limit);
    }

    public Optional<WorkflowExecution> findById(String executionId) {
        List<WorkflowExecution> rows = jdbc.query("""
            SELECT * FROM workflow_execution WHERE execution_id = ?
            """, (rs, rowNum) -> map(rs), executionId);
        return rows.stream().findFirst();
    }

    public List<WorkflowExecution> findUnviewed() {
        return jdbc.query("""
            SELECT * FROM workflow_execution WHERE last_viewed_at IS NULL ORDER BY fired_at DESC
            """, (rs, rowNum) -> map(rs));
    }

    private WorkflowExecution map(ResultSet rs) throws SQLException {
        Timestamp clearedAt = rs.getTimestamp("cleared_at");
        Timestamp lastViewedAt = rs.getTimestamp("last_viewed_at");
        List<Map<String, Object>> results;
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parsed = mapper.readValue(rs.getString("action_results"), List.class);
            results = parsed;
        } catch (Exception e) {
            log.warn("Skipping invalid action_results JSON: {}", e.getMessage());
            results = List.of();
        }
        return new WorkflowExecution(
            rs.getString("execution_id"), rs.getString("workflow_id"), rs.getString("triggered_by_event_id"),
            rs.getTimestamp("fired_at").toInstant(), clearedAt == null ? null : clearedAt.toInstant(),
            rs.getString("cleared_by"), results, lastViewedAt == null ? null : lastViewedAt.toInstant());
    }

    private String toJson(List<Map<String, Object>> results) {
        try {
            return mapper.writeValueAsString(results == null ? List.of() : results);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize action results", e);
        }
    }
}
