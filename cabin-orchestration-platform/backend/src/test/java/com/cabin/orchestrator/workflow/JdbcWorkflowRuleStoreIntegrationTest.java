package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Postgres container is static/shared across every @Test in this class
 * (same pattern JdbcDeviceDiscoveryStoreIntegrationTest uses) -- but unlike
 * that test, several methods here intentionally reuse the same
 * trigger_definition_id ("trigger_water_leak_detected") to exercise
 * realistic matching behavior, so leftover rows from an earlier test would
 * otherwise silently pollute a later test's findByTrigger() count. Truncate
 * before each test rather than hand-picking unique ids everywhere.
 */
@Testcontainers
class JdbcWorkflowRuleStoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcWorkflowRuleStore newStore() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcWorkflowRuleStore(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @BeforeEach
    void cleanTables() {
        // newStore() runs JdbcWorkflowRuleStore's CREATE TABLE IF NOT EXISTS
        // as a side effect -- on the very first test in the class, nothing
        // has constructed a store yet, so the tables don't exist before this
        // runs. Call it first (result unused) purely to guarantee the tables
        // exist, then truncate.
        newStore();
        new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
            .execute("TRUNCATE TABLE workflow_action, workflow_rule CASCADE");
    }

    private WorkflowRule compoundLeakWorkflow(String workflowId) {
        return new WorkflowRule(
            workflowId, "Leak shutoff", "cabin", "DEVICE_EVENT",
            "trigger_water_leak_detected", null, true, "AUTO_ON_CLEAR", null,
            Instant.now(), "test",
            List.of(
                new WorkflowAction(workflowId + "-a1", workflowId, 0, "notify_critical", null, Map.of(), null),
                new WorkflowAction(workflowId + "-a2", workflowId, 1, "action_main_water_valve_off",
                    "z2m-main_water_valve", Map.of("state", "OFF"), null)));
    }

    @Test
    void ruleAndOrderedActionsSurviveANewStoreInstance() {
        JdbcWorkflowRuleStore first = newStore();
        first.save(compoundLeakWorkflow("wf-1"));

        JdbcWorkflowRuleStore restarted = newStore();
        Optional<WorkflowRule> restored = restarted.findById("wf-1");

        assertTrue(restored.isPresent());
        assertEquals("Leak shutoff", restored.get().name());
        assertEquals(2, restored.get().actions().size());
        assertEquals("notify_critical", restored.get().actions().get(0).actionDefinitionId());
        assertEquals("action_main_water_valve_off", restored.get().actions().get(1).actionDefinitionId());
        assertEquals("z2m-main_water_valve", restored.get().actions().get(1).targetDeviceId());
    }

    @Test
    void savingAgainReplacesTheActionListRatherThanAppending() {
        JdbcWorkflowRuleStore store = newStore();
        store.save(compoundLeakWorkflow("wf-2"));

        WorkflowRule trimmed = new WorkflowRule(
            "wf-2", "Leak shutoff", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(new WorkflowAction("wf-2-a1", "wf-2", 0, "log_event", null, Map.of(), null)));
        store.save(trimmed);

        WorkflowRule reloaded = store.findById("wf-2").orElseThrow();
        assertEquals(1, reloaded.actions().size(), "old action rows must be replaced, not accumulated");
        assertEquals("log_event", reloaded.actions().get(0).actionDefinitionId());
    }

    @Test
    void findByTriggerMatchesGenericDeviceNullRuleAgainstAnySourceDevice() {
        JdbcWorkflowRuleStore store = newStore();
        store.save(compoundLeakWorkflow("wf-3")); // triggerDeviceId=null -> matches any leak sensor

        List<WorkflowRule> matches = store.findByTrigger("trigger_water_leak_detected", "z2m-leak_mech_room");

        assertEquals(1, matches.size());
        assertEquals("wf-3", matches.get(0).workflowId());
    }

    @Test
    void findByTriggerExcludesDisabledRules() {
        JdbcWorkflowRuleStore store = newStore();
        WorkflowRule disabled = new WorkflowRule(
            "wf-4", "Disabled leak rule", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            false, "AUTO_ON_CLEAR", null, Instant.now(), "test", List.of());
        store.save(disabled);

        List<WorkflowRule> matches = store.findByTrigger("trigger_water_leak_detected", "z2m-leak_mech_room");

        assertTrue(matches.isEmpty());
    }

    @Test
    void findByTriggerExcludesManualTriggerKindRules() {
        JdbcWorkflowRuleStore store = newStore();
        WorkflowRule manualReopen = new WorkflowRule(
            "wf-5", "Reopen valve", "cabin", "MANUAL", null, null,
            true, "MANUAL_ONLY", null, Instant.now(), "test", // parentWorkflowId null -- this test doesn't exercise the layered-workflow FK link, just MANUAL exclusion
            List.of(new WorkflowAction("wf-5-a1", "wf-5", 0, "action_main_water_valve_open",
                "z2m-main_water_valve", Map.of(), null)));
        store.save(manualReopen);

        List<WorkflowRule> matches = store.findByTrigger("trigger_water_leak_detected", "z2m-leak_mech_room");

        assertTrue(matches.isEmpty(), "MANUAL rules must never auto-fire from a device event");
        assertEquals("wf-5", store.findById("wf-5").orElseThrow().workflowId(), "but must still be independently readable/fireable");
    }
}
