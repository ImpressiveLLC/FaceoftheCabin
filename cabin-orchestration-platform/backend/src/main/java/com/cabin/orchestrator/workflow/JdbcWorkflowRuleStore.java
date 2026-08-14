package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Persists human-configured workflow rules (trigger + ordered actions).
 * Same idempotent CREATE TABLE IF NOT EXISTS + upsert pattern as
 * JdbcDeviceDiscoveryStore/JdbcDeviceLifecycleStore -- see those for the
 * precedent this follows.
 */
@Repository
public class JdbcWorkflowRuleStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcWorkflowRuleStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcWorkflowRuleStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS workflow_rule (
              workflow_id           TEXT PRIMARY KEY,
              name                  TEXT NOT NULL,
              location              TEXT NOT NULL,
              trigger_kind          TEXT NOT NULL DEFAULT 'DEVICE_EVENT',
              trigger_definition_id TEXT,
              trigger_device_id     TEXT,
              enabled               BOOLEAN NOT NULL DEFAULT true,
              reset_mode            TEXT NOT NULL DEFAULT 'AUTO_ON_CLEAR',
              parent_workflow_id    TEXT REFERENCES workflow_rule(workflow_id),
              created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
              created_by            TEXT
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS workflow_action (
              action_id             TEXT PRIMARY KEY,
              workflow_id           TEXT REFERENCES workflow_rule(workflow_id) ON DELETE CASCADE,
              step_order            INTEGER NOT NULL,
              action_definition_id  TEXT NOT NULL,
              target_device_id      TEXT,
              action_config         JSONB
            )""");
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS workflow_action_workflow_idx
            ON workflow_action (workflow_id, step_order)""");
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS workflow_rule_trigger_idx
            ON workflow_rule (trigger_definition_id, trigger_device_id) WHERE enabled""");
    }

    /** Upserts the rule row and fully replaces its action list (delete+reinsert -- action lists are small and always sent whole from the UI/API). */
    public void save(WorkflowRule rule) {
        jdbc.update("""
            INSERT INTO workflow_rule (workflow_id, name, location, trigger_kind, trigger_definition_id,
              trigger_device_id, enabled, reset_mode, parent_workflow_id, created_at, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (workflow_id) DO UPDATE SET
              name = EXCLUDED.name, location = EXCLUDED.location, trigger_kind = EXCLUDED.trigger_kind,
              trigger_definition_id = EXCLUDED.trigger_definition_id, trigger_device_id = EXCLUDED.trigger_device_id,
              enabled = EXCLUDED.enabled, reset_mode = EXCLUDED.reset_mode, parent_workflow_id = EXCLUDED.parent_workflow_id
            """,
            rule.workflowId(), rule.name(), rule.location(), rule.triggerKind(), rule.triggerDefinitionId(),
            rule.triggerDeviceId(), rule.enabled(), rule.resetMode(), rule.parentWorkflowId(),
            Timestamp.from(rule.createdAt()), rule.createdBy());

        jdbc.update("DELETE FROM workflow_action WHERE workflow_id = ?", rule.workflowId());
        for (WorkflowAction action : rule.actions()) {
            jdbc.update("""
                INSERT INTO workflow_action (action_id, workflow_id, step_order, action_definition_id,
                  target_device_id, action_config)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """,
                action.actionId(), rule.workflowId(), action.stepOrder(), action.actionDefinitionId(),
                action.targetDeviceId(), toJson(action.actionConfig()));
        }
    }

    public List<WorkflowRule> loadAll() {
        Map<String, WorkflowRule> rules = new LinkedHashMap<>();
        jdbc.query("SELECT * FROM workflow_rule", rs -> {
            String id = rs.getString("workflow_id");
            try {
                rules.put(id, new WorkflowRule(
                    id, rs.getString("name"), rs.getString("location"), rs.getString("trigger_kind"),
                    rs.getString("trigger_definition_id"), rs.getString("trigger_device_id"),
                    rs.getBoolean("enabled"), rs.getString("reset_mode"), rs.getString("parent_workflow_id"),
                    rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), new ArrayList<>()));
            } catch (Exception e) {
                log.warn("Skipping invalid workflow_rule row {}: {}", id, e.getMessage());
            }
        });
        jdbc.query("SELECT * FROM workflow_action ORDER BY workflow_id, step_order", rs -> {
            String workflowId = rs.getString("workflow_id");
            WorkflowRule rule = rules.get(workflowId);
            if (rule == null) return;
            try {
                rule.actions().add(new WorkflowAction(
                    rs.getString("action_id"), workflowId, rs.getInt("step_order"),
                    rs.getString("action_definition_id"), rs.getString("target_device_id"),
                    fromJson(rs.getString("action_config"))));
            } catch (Exception e) {
                log.warn("Skipping invalid workflow_action row: {}", e.getMessage());
            }
        });
        return new ArrayList<>(rules.values());
    }

    /** Enabled DEVICE_EVENT rules matching this trigger; a null triggerDeviceId on the rule matches any device. */
    public List<WorkflowRule> findByTrigger(String triggerDefinitionId, String sourceDeviceId) {
        return loadAll().stream()
            .filter(WorkflowRule::enabled)
            .filter(r -> "DEVICE_EVENT".equals(r.triggerKind()))
            .filter(r -> Objects.equals(r.triggerDefinitionId(), triggerDefinitionId))
            .filter(r -> r.triggerDeviceId() == null || Objects.equals(r.triggerDeviceId(), sourceDeviceId))
            .collect(Collectors.toList());
    }

    public Optional<WorkflowRule> findById(String workflowId) {
        return loadAll().stream().filter(r -> r.workflowId().equals(workflowId)).findFirst();
    }

    public void delete(String workflowId) {
        jdbc.update("DELETE FROM workflow_rule WHERE workflow_id = ?", workflowId);
    }

    private String toJson(Map<String, Object> config) {
        try {
            return mapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize action config", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(json, Map.class);
            return parsed;
        } catch (Exception e) {
            log.warn("Skipping invalid action_config JSON: {}", e.getMessage());
            return Map.of();
        }
    }
}
