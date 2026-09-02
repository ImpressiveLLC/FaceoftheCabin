package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.ontology.OntologyLookupService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.workflow.CommandCatalogService;
import com.cabin.orchestrator.workflow.JdbcWorkflowExecutionStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowRuleStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowVocabularyStore;
import com.cabin.orchestrator.workflow.WorkflowActionTargetValidator;
import com.cabin.orchestrator.workflow.WorkflowRuleService;
import com.cabin.orchestrator.workflow.model.ActionVocabularyEntry;
import com.cabin.orchestrator.workflow.model.TriggerVocabularyEntry;
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
import java.util.Set;

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
    private JdbcWorkflowVocabularyStore vocabularyStore;
    private DeviceRegistry deviceRegistry;

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
        deviceRegistry = new DeviceRegistry(List.of());
        // Needed since WorkflowActionTargetValidator started enforcing real
        // targetDeviceId validation -- every fixture workflow below that
        // targets the main valve now needs it to actually exist and be
        // ASSIGNED, matching DeviceDiscoveryController.applyNew()'s real
        // CANDIDATE -> ACCEPT -> saveConfiguration flow (same helper
        // WorkflowRuleServiceTest already uses for the same reason).
        deviceRegistry.registerCandidate(new DeviceDescriptor(
            "z2m-main_water_valve", "Main Water Valve", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/main_water_valve", true, "cabin"), Map.of());
        deviceRegistry.applyLifecycleAction("z2m-main_water_valve", DeviceLifecycleAction.ACCEPT);
        deviceRegistry.saveConfiguration("z2m-main_water_valve", "Main Water Valve", true);
        WorkflowRuleService workflowRuleService = new WorkflowRuleService(ruleStore, executionStore,
            deviceRegistry, new CommandCatalogService(deviceRegistry), new EventPublisher());
        vocabularyStore = new JdbcWorkflowVocabularyStore(jdbc);
        // No real ontology.yaml mount in this no-Spring-context test --
        // ontologyPath stays null, so listCandidate*() degrades to empty
        // (parseElements()'s own IOException|RuntimeException catch),
        // same graceful-degradation path a fork with no docs/ bind mount
        // hits in production. Candidate-merging itself is covered in
        // isolation by OntologyLookupServiceTest, against a real fixture
        // file, rather than coupling this test to docs/ontology.yaml's
        // real repo-relative path.
        OntologyLookupService ontologyLookupService = new OntologyLookupService();
        WorkflowActionTargetValidator targetValidator = new WorkflowActionTargetValidator(deviceRegistry, vocabularyStore);
        // z2mAdapter is only dereferenced from checkHealth()'s tryActiveRecovery()
        // path, which this no-Spring-context test never triggers (no @Scheduled
        // cycle runs outside a real Spring context) -- null is safe here.
        DeviceHealthMonitor healthMonitor = new DeviceHealthMonitor(deviceRegistry, null);
        controller = new RulesController(ruleStore, executionStore, workflowRuleService, vocabularyStore,
            ontologyLookupService, targetValidator, healthMonitor);
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

    // 2026-08-21: the real fix for "I can't configure anything beyond leak
    // detection and the device picker only shows 'any device'" -- these
    // endpoints give WorkflowCreateForm a DB-backed catalog to render
    // instead of App.jsx's old hardcoded WORKFLOW_TRIGGERS/WORKFLOW_ACTIONS
    // arrays. See JdbcWorkflowVocabularyStore's own doc for the design.
    @Test
    void triggerVocabularyReturnsExactlyWhatWorkflowRuleServiceInterprets() {
        List<TriggerVocabularyEntry> triggers = controller.triggerVocabulary();

        // 2026-08-21 (Part E): 3 -> 22 after wiring the real triggers already
        // flowing from the 13 paired devices/cameras plus armed/presence/HA.
        // 2026-08-27: 24 -> 26 after adding trigger_mold_risk_detected/_cleared.
        assertEquals(26, triggers.size());
        assertTrue(triggers.stream().allMatch(TriggerVocabularyEntry::supported));
        assertTrue(triggers.stream().anyMatch(t -> t.id().equals("trigger_water_leak_detected") && t.label().equals("Water leak detected")));
        assertTrue(triggers.stream().anyMatch(t -> t.id().equals("trigger_camera_detection") && "CAMERA".equals(t.appliesToDeviceType())));
        assertTrue(triggers.stream().anyMatch(t -> t.id().equals("trigger_door_contact_opened") && "CONTACT_SENSOR".equals(t.appliesToDeviceType())));
        assertTrue(triggers.stream().anyMatch(t -> t.id().equals("trigger_ha_entity_state_changed")));
        // appliesToField (not appliesToDeviceType) is what actually scopes this
        // trigger's device picker now -- see TriggerVocabularyEntry's own doc
        // and App.jsx's triggerScopedDevices for why a single device-type
        // string can't express "reported by either of two types."
        assertTrue(triggers.stream().anyMatch(t -> t.id().equals("trigger_mold_risk_detected")
            && t.appliesToDeviceType() == null && "humidity".equals(t.appliesToField())));
        assertTrue(triggers.stream().anyMatch(t -> t.id().equals("trigger_freeze_risk_detected")
            && "TEMPERATURE_SENSOR".equals(t.appliesToDeviceType()) && "temperature".equals(t.appliesToField())));
    }

    @Test
    void actionVocabularyMarksTheReopenActionPrivilegedAndLocksItsTargetDevice() {
        List<ActionVocabularyEntry> actions = controller.actionVocabulary();

        assertEquals(4, actions.size());
        assertTrue(actions.stream().allMatch(ActionVocabularyEntry::supported));
        ActionVocabularyEntry reopen = actions.stream().filter(a -> a.id().equals("action_main_water_valve_open")).findFirst().orElseThrow();
        assertTrue(reopen.privileged());
        assertEquals("z2m-main_water_valve", reopen.targetDeviceId(), "instance-specific action must ship a fixed target, not a free picker");
        ActionVocabularyEntry notify = actions.stream().filter(a -> a.id().equals("notify_critical")).findFirst().orElseThrow();
        assertFalse(notify.privileged());
        assertFalse(notify.needsTarget());
    }

    @Test
    void vocabularyDegradesToSupportedOnlyWhenNoOntologyFileIsMounted() {
        // ontologyLookupService in this test's setUp has no real ontologyPath
        // (no Spring @Value injection outside a container) -- same
        // graceful-degradation path a fresh fork with no docs/ bind mount
        // hits in production (see OntologyLookupService's own class doc).
        // Real candidate-merging is covered by OntologyLookupServiceTest.
        assertEquals(26, controller.triggerVocabulary().size());
        assertEquals(4, controller.actionVocabulary().size());
    }

    /** Mirrors setUp()'s own main_water_valve registration, for a second device. */
    private void registerAssignedDevice(String deviceId, String name, Set<DeviceCapability> capabilities) {
        deviceRegistry.registerCandidate(new DeviceDescriptor(
            deviceId, name, DeviceType.HOME_ASSISTANT_ENTITY, capabilities,
            "mqtt", "zigbee2mqtt/" + deviceId, true, "cabin"), Map.of());
        deviceRegistry.applyLifecycleAction(deviceId, DeviceLifecycleAction.ACCEPT);
        deviceRegistry.saveConfiguration(deviceId, name, true);
    }

    // Phase 3 (Part C) -- workflow health, added 2026-09-02.
    @Test
    void listWorkflowsAttachesHealthyStatusAsAFlatSiblingOfTheWorkflowFields() throws Exception {
        controller.createWorkflow(
            deviceEventWorkflow("wf-health-good", "action_main_water_valve_off"), authedRequest("nate@example.com"));

        List<RulesController.WorkflowRuleView> views = controller.listWorkflows();
        RulesController.WorkflowRuleView view = views.stream()
            .filter(v -> v.rule().workflowId().equals("wf-health-good")).findFirst().orElseThrow();
        assertEquals("HEALTHY", view.health().status());

        // @JsonUnwrapped must make workflowId and health flat siblings in the
        // same JSON object, not health nested under a "rule" wrapper key --
        // this is the "no shape change for existing consumers" guarantee.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(mapper.writeValueAsString(view));
        assertEquals("wf-health-good", json.get("workflowId").asText());
        assertEquals("HEALTHY", json.get("health").get("status").asText());
        assertFalse(json.has("rule"), "WorkflowRule's fields must be unwrapped, not nested under a 'rule' key");
    }

    @Test
    void listWorkflowsMarksAWorkflowBrokenWhenItsTargetDeviceIsNoLongerAssigned() {
        controller.createWorkflow(
            deviceEventWorkflow("wf-health-broken", "action_main_water_valve_off"), authedRequest("nate@example.com"));
        // Same real path an admin removing/ignoring a device goes through --
        // the workflow row itself is untouched, only the device's lifecycle changed.
        deviceRegistry.applyLifecycleAction("z2m-main_water_valve", DeviceLifecycleAction.IGNORE);

        RulesController.WorkflowRuleView view = controller.listWorkflows().stream()
            .filter(v -> v.rule().workflowId().equals("wf-health-broken")).findFirst().orElseThrow();

        assertEquals("BROKEN", view.health().status());
        assertFalse(view.health().actions().get(0).activeUseAllowed());
    }

    @Test
    void retargetActionUpdatesToANewValidTargetDeviceAndPersistsIt() {
        registerAssignedDevice("z2m-backup_valve", "Backup Valve", Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY));
        controller.createWorkflow(
            deviceEventWorkflow("wf-retarget-good", "action_main_water_valve_off"), authedRequest("nate@example.com"));

        Map<String, Object> result = controller.retargetAction(
            "wf-retarget-good", "wf-retarget-good-a0", Map.of("targetDeviceId", "z2m-backup_valve"));

        assertFalse(result.containsKey("error"));
        WorkflowRule updated = ruleStore.findById("wf-retarget-good").orElseThrow();
        assertEquals("z2m-backup_valve", updated.actions().get(0).targetDeviceId());
    }

    @Test
    void retargetActionRejectsANonexistentReplacementDeviceAndLeavesTheWorkflowUnchanged() {
        controller.createWorkflow(
            deviceEventWorkflow("wf-retarget-bad", "action_main_water_valve_off"), authedRequest("nate@example.com"));

        Map<String, Object> result = controller.retargetAction(
            "wf-retarget-bad", "wf-retarget-bad-a0", Map.of("targetDeviceId", "z2m-does_not_exist"));

        assertTrue(result.containsKey("error"));
        WorkflowRule unchanged = ruleStore.findById("wf-retarget-bad").orElseThrow();
        assertEquals("z2m-main_water_valve", unchanged.actions().get(0).targetDeviceId(),
            "a rejected retarget must not persist the invalid replacement");
    }

    @Test
    void retargetActionRejectsAReplacementLackingTheRequiredCapability() {
        registerAssignedDevice("z2m-sensor_only", "Sensor Only", Set.of(DeviceCapability.TELEMETRY));
        controller.createWorkflow(
            deviceEventWorkflow("wf-retarget-nocap", "action_main_water_valve_off"), authedRequest("nate@example.com"));

        Map<String, Object> result = controller.retargetAction(
            "wf-retarget-nocap", "wf-retarget-nocap-a0", Map.of("targetDeviceId", "z2m-sensor_only"));

        assertTrue(result.containsKey("error"));
        assertTrue(((String) result.get("error")).contains("COMMAND"));
    }

    @Test
    void retargetActionReturnsNotFoundForAnUnknownWorkflow() {
        Map<String, Object> result = controller.retargetAction(
            "wf-does-not-exist", "some-action", Map.of("targetDeviceId", "z2m-main_water_valve"));

        assertEquals("not found", result.get("error"));
    }

    @Test
    void retargetActionReturnsAnErrorForAnUnknownActionId() {
        controller.createWorkflow(
            deviceEventWorkflow("wf-retarget-badaction", "action_main_water_valve_off"), authedRequest("nate@example.com"));

        Map<String, Object> result = controller.retargetAction(
            "wf-retarget-badaction", "no-such-action-id", Map.of("targetDeviceId", "z2m-main_water_valve"));

        assertTrue(result.containsKey("error"));
    }

    @Test
    void retargetActionIsAllowedEvenForTheInstanceLockedReopenAction() {
        // Deliberate: WorkflowCreateForm's UI lock and validateReopenGuard()
        // both exist to prevent MISTARGETING/misuse at creation time -- this
        // endpoint is the supervised, already-validated alternative to
        // editing Postgres directly when a genuine replacement is needed,
        // and retargetAction() never calls validateReopenGuard() (that guard
        // is about which TRIGGER kind an action may attach to, unrelated to
        // which device a targetDeviceId points at).
        registerAssignedDevice("z2m-backup_valve", "Backup Valve", Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY));
        WorkflowRule manualReopen = new WorkflowRule("wf-reopen-retarget", "Reopen valve", "cabin", "MANUAL",
            null, null, true, "MANUAL_ONLY", null, Instant.now(), "test",
            List.of(new WorkflowAction("wf-reopen-retarget-a0", "wf-reopen-retarget", 0,
                "action_main_water_valve_open", "z2m-main_water_valve", Map.of(), null)));
        ruleStore.save(manualReopen);

        Map<String, Object> result = controller.retargetAction(
            "wf-reopen-retarget", "wf-reopen-retarget-a0", Map.of("targetDeviceId", "z2m-backup_valve"));

        assertFalse(result.containsKey("error"));
        assertEquals("z2m-backup_valve", ruleStore.findById("wf-reopen-retarget").orElseThrow().actions().get(0).targetDeviceId());
    }
}
