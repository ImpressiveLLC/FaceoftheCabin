package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.workflow.JdbcWorkflowExecutionStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowRuleStore;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Same no-Spring-context Testcontainers pattern as DeviceDiscoveryControllerTest
 * -- the controller is instantiated directly, real Jdbc stores, a
 * MockHttpServletRequest carrying GoogleAuthInterceptor.REQUEST_ATTR_EMAIL
 * stands in for what the real interceptor would have set on an
 * authenticated request (the interceptor itself is exercised separately in
 * GoogleAuthInterceptorTest).
 */
@Testcontainers
class RulesControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private RulesController controller;
    private JdbcWorkflowRuleStore ruleStore;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ruleStore = new JdbcWorkflowRuleStore(jdbc, mapper);
        JdbcWorkflowExecutionStore executionStore = new JdbcWorkflowExecutionStore(jdbc, mapper);
        jdbc.execute("TRUNCATE TABLE workflow_execution, workflow_action, workflow_rule CASCADE");
        controller = new RulesController(ruleStore, executionStore);
    }

    private MockHttpServletRequest authedRequest(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, email);
        return request;
    }

    private WorkflowRule deviceEventWorkflow(String id, String... actionDefinitionIds) {
        List<WorkflowAction> actions = new java.util.ArrayList<>();
        int i = 0;
        for (String actionId : actionDefinitionIds) {
            actions.add(new WorkflowAction(id + "-a" + i, id, i, actionId, "z2m-main_water_valve", Map.of()));
            i++;
        }
        return new WorkflowRule(id, "Test workflow", "cabin", "DEVICE_EVENT",
            "trigger_water_leak_detected", null, true /* client claims enabled, must be ignored */,
            "AUTO_ON_CLEAR", null, Instant.now(), "client-supplied-should-be-ignored", actions);
    }

    @Test
    void newWorkflowIsAlwaysCreatedDisabledRegardlessOfRequestBody() {
        Map<String, Object> result = controller.createWorkflow(
            deviceEventWorkflow("wf-draft-test", "notify_critical"), authedRequest("nate@example.com"));

        assertEquals(false, result.get("enabled"));
        WorkflowRule stored = ruleStore.findById("wf-draft-test").orElseThrow();
        assertFalse(stored.enabled(), "even though the request body claimed enabled=true");
    }

    @Test
    void createdByIsDerivedFromTheAuthenticatedActorNotTheRequestBody() {
        controller.createWorkflow(deviceEventWorkflow("wf-actor-test", "notify_critical"),
            authedRequest("real-user@example.com"));

        WorkflowRule stored = ruleStore.findById("wf-actor-test").orElseThrow();
        assertEquals("real-user@example.com", stored.createdBy());
        assertNotEquals("client-supplied-should-be-ignored", stored.createdBy());
    }

    @Test
    void createdByFallsBackToUnknownWhenNoAuthAttributeIsPresent() {
        controller.createWorkflow(deviceEventWorkflow("wf-noauth-test", "notify_critical"),
            new MockHttpServletRequest()); // no REQUEST_ATTR_EMAIL set

        WorkflowRule stored = ruleStore.findById("wf-noauth-test").orElseThrow();
        assertEquals("unknown", stored.createdBy());
    }

    @Test
    void deviceEventWorkflowCannotBeCreatedWithThePrivilegedReopenAction() {
        Map<String, Object> result = controller.createWorkflow(
            deviceEventWorkflow("wf-bad-reopen", "action_main_water_valve_open"), authedRequest("nate@example.com"));

        assertTrue(result.containsKey("error"));
        assertTrue(ruleStore.findById("wf-bad-reopen").isEmpty(), "the invalid workflow must never be persisted");
    }

    @Test
    void deviceEventWorkflowWithOnlyTheCloseActionIsAccepted() {
        Map<String, Object> result = controller.createWorkflow(
            deviceEventWorkflow("wf-good-close", "notify_critical", "action_main_water_valve_off"),
            authedRequest("nate@example.com"));

        assertFalse(result.containsKey("error"));
        assertTrue(ruleStore.findById("wf-good-close").isPresent());
    }

    @Test
    void reopenGuardIsAlsoEnforcedOnActivateNotJustCreate() {
        // Simulates a row that predates the guard (or was inserted directly) --
        // activation must still refuse to arm it.
        ruleStore.save(new WorkflowRule("wf-legacy-bad", "Legacy", "cabin", "DEVICE_EVENT",
            "trigger_water_leak_detected", null, false, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(new WorkflowAction("wf-legacy-bad-a0", "wf-legacy-bad", 0,
                "action_main_water_valve_open", "z2m-main_water_valve", Map.of()))));

        Map<String, Object> result = controller.activateWorkflow("wf-legacy-bad");

        assertTrue(result.containsKey("error"));
        assertFalse(ruleStore.findById("wf-legacy-bad").orElseThrow().enabled());
    }

    @Test
    void activateThenDeactivateRoundTrips() {
        controller.createWorkflow(deviceEventWorkflow("wf-lifecycle", "notify_critical"), authedRequest("nate@example.com"));
        assertFalse(ruleStore.findById("wf-lifecycle").orElseThrow().enabled());

        controller.activateWorkflow("wf-lifecycle");
        assertTrue(ruleStore.findById("wf-lifecycle").orElseThrow().enabled());

        controller.deactivateWorkflow("wf-lifecycle");
        assertFalse(ruleStore.findById("wf-lifecycle").orElseThrow().enabled());
    }

    @Test
    void manualFireEndpointRejectsNonManualWorkflows() {
        controller.createWorkflow(deviceEventWorkflow("wf-not-manual", "notify_critical"), authedRequest("nate@example.com"));
        controller.activateWorkflow("wf-not-manual");

        Map<String, Object> result = controller.fireManual("wf-not-manual", authedRequest("nate@example.com"));

        assertTrue(result.containsKey("error"));
    }
}
