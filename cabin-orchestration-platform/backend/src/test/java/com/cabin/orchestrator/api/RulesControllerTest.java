package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.workflow.CommandCatalogService;
import com.cabin.orchestrator.workflow.JdbcWorkflowExecutionStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowRuleStore;
import com.cabin.orchestrator.workflow.WorkflowRuleService;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
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
    private JdbcWorkflowExecutionStore executionStore;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ruleStore = new JdbcWorkflowRuleStore(jdbc, mapper);
        executionStore = new JdbcWorkflowExecutionStore(jdbc, mapper);
        jdbc.execute("TRUNCATE TABLE workflow_execution, workflow_action, workflow_rule CASCADE");
        // No real ProtocolAdapter needed -- this file's manual-fire tests
        // only use notify_critical/log_event actions, neither of which
        // touches DeviceRegistry.sendCommand().
        DeviceRegistry deviceRegistry = new DeviceRegistry(List.of());
        WorkflowRuleService workflowRuleService = new WorkflowRuleService(ruleStore, executionStore,
            deviceRegistry, new CommandCatalogService(deviceRegistry), new EventPublisher());
        controller = new RulesController(ruleStore, executionStore, workflowRuleService);
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
            actions.add(new WorkflowAction(id + "-a" + i, id, i, actionId, "z2m-main_water_valve", Map.of(), null));
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
    void theClearedTriggerVariantIsStillDeviceEventKindAndStillCannotReopenTheValve() {
        // Regression guard for the 2026-08-21 "condition cleared" trigger
        // (WorkflowRuleService.CLEARED_TRIGGER_IDS): it's safe by
        // construction, not a new special case -- this pins that a
        // trigger_water_leak_cleared-triggered workflow is exactly as
        // DEVICE_EVENT as any other and gets the same guard.
        WorkflowRule onClearReopen = new WorkflowRule("wf-bad-reopen-on-clear", "Bad auto-reopen", "cabin",
            "DEVICE_EVENT", "trigger_water_leak_cleared", null, true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(new WorkflowAction("wf-bad-reopen-on-clear-a0", "wf-bad-reopen-on-clear", 0,
                "action_main_water_valve_open", "z2m-main_water_valve", Map.of(), null)));

        Map<String, Object> result = controller.createWorkflow(onClearReopen, authedRequest("nate@example.com"));

        assertTrue(result.containsKey("error"));
        assertTrue(ruleStore.findById("wf-bad-reopen-on-clear").isEmpty());
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
                "action_main_water_valve_open", "z2m-main_water_valve", Map.of(), null))));

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

    private WorkflowRule manualReopenWorkflow(String id) {
        return new WorkflowRule(id, "Reopen valve", "cabin", "MANUAL", null, null,
            false, "MANUAL_ONLY", null, Instant.now(), "client-supplied-should-be-ignored",
            List.of(new WorkflowAction(id + "-a0", id, 0, "action_main_water_valve_open", "z2m-main_water_valve", Map.of(), null)));
    }

    @Test
    void manualFireEndpointRejectsAnInactiveManualWorkflow() {
        controller.createWorkflow(manualReopenWorkflow("wf-manual-draft"), authedRequest("nate@example.com"));
        // deliberately not activated

        Map<String, Object> result = controller.fireManual("wf-manual-draft", authedRequest("nate@example.com"));

        assertTrue(result.containsKey("error"));
        assertTrue(executionStore.recentFor("wf-manual-draft", 10).isEmpty());
    }

    @Test
    void manualFireEndpointRejectsAnUnknownWorkflowId() {
        Map<String, Object> result = controller.fireManual("does-not-exist", authedRequest("nate@example.com"));

        assertTrue(result.containsKey("error"));
    }

    @Test
    void activatingAManualReopenWorkflowIsAllowedSinceItIsNotDeviceEventTriggered() {
        // The privileged-action guard only blocks DEVICE_EVENT triggers --
        // confirms MANUAL stays the one legitimate path for this action.
        Map<String, Object> created = controller.createWorkflow(manualReopenWorkflow("wf-manual-ok"), authedRequest("nate@example.com"));
        assertFalse(created.containsKey("error"));

        Map<String, Object> activated = controller.activateWorkflow("wf-manual-ok");

        assertFalse(activated.containsKey("error"));
        assertTrue(ruleStore.findById("wf-manual-ok").orElseThrow().enabled());
    }

    @Test
    void manualFireEndpointActuallyRunsTheWorkflowAndRecordsAnExecution() {
        controller.createWorkflow(manualReopenWorkflow("wf-manual-fire"), authedRequest("nate@example.com"));
        controller.activateWorkflow("wf-manual-fire");

        Map<String, Object> result = controller.fireManual("wf-manual-fire", authedRequest("nate@example.com"));

        assertFalse(result.containsKey("error"));
        assertEquals(Boolean.TRUE, result.get("fired"));
        WorkflowExecution execution = executionStore.recentFor("wf-manual-fire", 10).get(0);
        assertNull(execution.triggeredByEventId(), "a manual fire has no source event");
        assertEquals("MANUAL:nate@example.com", execution.clearedBy());
        assertNotNull(execution.clearedAt(), "manual fires self-clear immediately");
    }

    @Test
    void tappingFireTwiceCreatesTwoSeparateExecutionsNotOne() {
        // Unlike a device-triggered execution, there is no source event to
        // dedupe against -- every tap is a distinct, deliberate human action.
        controller.createWorkflow(manualReopenWorkflow("wf-manual-repeat"), authedRequest("nate@example.com"));
        controller.activateWorkflow("wf-manual-repeat");

        controller.fireManual("wf-manual-repeat", authedRequest("nate@example.com"));
        controller.fireManual("wf-manual-repeat", authedRequest("nate@example.com"));

        assertEquals(2, executionStore.recentFor("wf-manual-repeat", 10).size());
    }
}
