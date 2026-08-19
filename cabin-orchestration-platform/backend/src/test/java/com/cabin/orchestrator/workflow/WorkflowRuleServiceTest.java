package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.*;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres (via the real Jdbc stores -- this project's own convention
 * for anything JdbcTemplate/SQL-dependent, see EventPipelineIntegrationTest's
 * doc comment), a recording fake for ProtocolAdapter/EventPublisher so
 * command/notify behavior is observable without a real MQTT broker or Kafka.
 *
 * Hardening tests added 2026-08-14 (external review of the first live
 * test) use short millisecond confirmation delays via ReflectionTestUtils
 * -- same @Value-outside-Spring pattern AutomationRuleServiceTest already
 * uses -- and a real (not simulated) DeviceRegistry.update() call to stand
 * in for what Zigbee2MqttAdapter would normally do when the device reports
 * its own state back.
 */
@Testcontainers
class WorkflowRuleServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static class RecordingAdapter implements ProtocolAdapter {
        final List<Map<String, Object>> commands = new ArrayList<>();
        boolean succeed = true;

        @Override public String adapterType() { return "mqtt"; }
        @Override public Optional<DeviceStatus> fetchState(DeviceDescriptor d) { return Optional.empty(); }
        @Override public boolean sendCommand(DeviceDescriptor d, String command, Object payload) {
            commands.add(Map.of("deviceId", d.deviceId(), "command", command, "payload", payload));
            return succeed;
        }
    }

    private static class RecordingEventPublisher extends EventPublisher {
        final List<CabinEvent> published = new ArrayList<>();
        @Override public void publish(CabinEvent event) { published.add(event); }
    }

    private JdbcWorkflowRuleStore ruleStore;
    private JdbcWorkflowExecutionStore executionStore;
    private DeviceRegistry deviceRegistry;
    private RecordingAdapter adapter;
    private RecordingEventPublisher eventPublisher;
    private WorkflowRuleService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ruleStore = new JdbcWorkflowRuleStore(jdbc, mapper);
        executionStore = new JdbcWorkflowExecutionStore(jdbc, mapper);
        // Postgres container is static/shared across every @Test -- truncate
        // so one test's workflow rows (and reused action_id primary keys
        // like "a1") never collide with or leak into another's assertions.
        jdbc.execute("TRUNCATE TABLE workflow_execution, workflow_action, workflow_rule CASCADE");
        adapter = new RecordingAdapter();
        deviceRegistry = new DeviceRegistry(List.of(adapter));
        registerCommandCapableDevice("z2m-main_water_valve", "Main Water Valve", DeviceType.HOME_ASSISTANT_ENTITY);
        eventPublisher = new RecordingEventPublisher();
        service = new WorkflowRuleService(ruleStore, executionStore, deviceRegistry,
            new CommandCatalogService(deviceRegistry), eventPublisher);
        // Fast by default for every test; confirmation-specific tests override further.
        ReflectionTestUtils.setField(service, "confirmationInitialDelayMillis", 50L);
        ReflectionTestUtils.setField(service, "confirmationFinalDelayMillis", 150L);
    }

    /** Mirrors DeviceDiscoveryController.applyNew()'s exact CANDIDATE -> ACCEPT -> saveConfiguration flow -- allowsActiveUse() is only true once ASSIGNED. */
    private void registerCommandCapableDevice(String deviceId, String name, DeviceType type) {
        deviceRegistry.registerCandidate(new DeviceDescriptor(
            deviceId, name, type, Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/" + deviceId.replaceFirst("^z2m-", ""), true, "cabin"), Map.of());
        deviceRegistry.applyLifecycleAction(deviceId, DeviceLifecycleAction.ACCEPT);
        deviceRegistry.saveConfiguration(deviceId, name, true);
    }

    private CabinEvent leakEvent(String deviceId) {
        return new CabinEvent(UUID.randomUUID().toString(), deviceId, "TELEMETRY", "CRITICAL",
            Instant.now(), Map.of("water_leak", true));
    }

    private CabinEvent leakClearedEvent(String deviceId) {
        return new CabinEvent(UUID.randomUUID().toString(), deviceId, "TELEMETRY", "INFO",
            Instant.now(), Map.of("water_leak", false));
    }

    private WorkflowRule compoundLeakWorkflow(String workflowId) {
        return new WorkflowRule(workflowId, "Leak shutoff", "cabin", "DEVICE_EVENT",
            "trigger_water_leak_detected", null, true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(
                new WorkflowAction(workflowId + "-a1", workflowId, 0, "notify_critical", null, Map.of()),
                new WorkflowAction(workflowId + "-a2", workflowId, 1, "action_main_water_valve_off",
                    "z2m-main_water_valve", Map.of())));
    }

    @Test
    void compoundWorkflowRunsNotifyAndValveOffInOrderAndRecordsAnExecution() {
        ruleStore.save(compoundLeakWorkflow("wf-compound"));

        service.evaluate(leakEvent("z2m-leak_mech_room"));

        assertEquals(1, adapter.commands.size());
        assertEquals("OFF", ((Map<?, ?>) adapter.commands.get(0).get("payload")).get("state"));
        assertEquals(1, eventPublisher.published.size());
        assertEquals("WORKFLOW_ACTION", eventPublisher.published.get(0).eventType());

        List<WorkflowExecution> executions = executionStore.recentFor("wf-compound", 10);
        assertEquals(1, executions.size());
        assertEquals(2, executions.get(0).actionResults().size());
        assertEquals(true, executions.get(0).actionResults().get(0).get("success"));
        assertEquals(true, executions.get(0).actionResults().get(1).get("success"));
        assertEquals("ACCEPTED", executions.get(0).actionResults().get(1).get("commandStatus"),
            "immediately after evaluate() returns, only the publish is proven -- confirmation is async");
    }

    @Test
    void aFailedActionDoesNotSkipLaterActionsInTheSameWorkflow() {
        adapter.succeed = false; // simulates the valve command failing
        ruleStore.save(new WorkflowRule(
            "wf-partial-fail", "Leak shutoff", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(
                new WorkflowAction("a1", "wf-partial-fail", 0, "action_main_water_valve_off",
                    "z2m-main_water_valve", Map.of()),
                new WorkflowAction("a2", "wf-partial-fail", 1, "notify_critical", null, Map.of()))));

        service.evaluate(leakEvent("z2m-leak_mech_room"));

        // The failed command must not prevent the notify step from still running.
        assertEquals(1, eventPublisher.published.size());
        List<WorkflowExecution> executions = executionStore.recentFor("wf-partial-fail", 10);
        assertEquals(false, executions.get(0).actionResults().get(0).get("success"));
        assertEquals("PUBLISH_FAILED", executions.get(0).actionResults().get(0).get("commandStatus"));
        assertEquals(true, executions.get(0).actionResults().get(1).get("success"));
    }

    @Test
    void twoIndependentWorkflowsOnTheSameTriggerBothFireNeitherBlockingTheOther() {
        ruleStore.save(new WorkflowRule(
            "wf-a", "Shutoff", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(new WorkflowAction("a1", "wf-a", 0, "action_main_water_valve_off", "z2m-main_water_valve", Map.of()))));
        ruleStore.save(new WorkflowRule(
            "wf-b", "Notify only", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", "wf-a", Instant.now(), "test",
            List.of(new WorkflowAction("b1", "wf-b", 0, "notify_critical", null, Map.of()))));

        service.evaluate(leakEvent("z2m-leak_mech_room"));

        assertEquals(1, executionStore.recentFor("wf-a", 10).size());
        assertEquals(1, executionStore.recentFor("wf-b", 10).size());
        assertEquals(1, adapter.commands.size());
        assertEquals(1, eventPublisher.published.size());
    }

    @Test
    void manualTriggerKindWorkflowNeverFiresFromADeviceEvent() {
        ruleStore.save(new WorkflowRule(
            "wf-manual", "Reopen valve", "cabin", "MANUAL", null, null,
            true, "MANUAL_ONLY", null, Instant.now(), "test",
            List.of(new WorkflowAction("m1", "wf-manual", 0, "action_main_water_valve_open", "z2m-main_water_valve", Map.of()))));

        service.evaluate(leakEvent("z2m-leak_mech_room"));

        assertTrue(executionStore.recentFor("wf-manual", 10).isEmpty());
        assertTrue(adapter.commands.isEmpty());
    }

    @Test
    void unrelatedTelemetryProducesNoExecutionAndNeverTouchesTheDeviceRegistry() {
        ruleStore.save(new WorkflowRule(
            "wf-c", "Leak shutoff", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(new WorkflowAction("c1", "wf-c", 0, "action_main_water_valve_off", "z2m-main_water_valve", Map.of()))));

        service.evaluate(new CabinEvent(UUID.randomUUID().toString(), "psi_mech_room", "TELEMETRY", "INFO",
            Instant.now(), Map.of("psi", 26.0)));

        assertTrue(adapter.commands.isEmpty());
        assertTrue(eventPublisher.published.isEmpty());
    }

    @Test
    void workflowActionEventsNeverReMatchThemselves() {
        ruleStore.save(new WorkflowRule(
            "wf-d", "Leak shutoff", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(new WorkflowAction("d1", "wf-d", 0, "action_main_water_valve_off", "z2m-main_water_valve", Map.of()))));

        // A WORKFLOW_ACTION event, even one that happens to carry water_leak=true, must be skipped outright.
        service.evaluate(new CabinEvent(UUID.randomUUID().toString(), "z2m-leak_mech_room", "WORKFLOW_ACTION",
            "CRITICAL", Instant.now(), Map.of("water_leak", true)));

        assertTrue(adapter.commands.isEmpty());
        assertTrue(executionStore.recentFor("wf-d", 10).isEmpty());
    }

    @Test
    void commandIsRejectedWhenTargetDeviceIsNotCommandCapable() {
        deviceRegistry.registerCandidate(new DeviceDescriptor(
            "z2m-temp_kitchen", "Kitchen Temp", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/temp_kitchen", true, "cabin"), Map.of());
        deviceRegistry.applyLifecycleAction("z2m-temp_kitchen", DeviceLifecycleAction.ACCEPT);
        deviceRegistry.saveConfiguration("z2m-temp_kitchen", "Kitchen Temp", true);
        ruleStore.save(new WorkflowRule(
            "wf-e", "Misconfigured", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(new WorkflowAction("e1", "wf-e", 0, "action_main_water_valve_off", "z2m-temp_kitchen", Map.of()))));

        service.evaluate(leakEvent("z2m-leak_mech_room"));

        assertTrue(adapter.commands.isEmpty(),
            "CommandCatalogService must reject a non-COMMAND-capable target before DeviceRegistry is ever called");
        WorkflowExecution execution = executionStore.recentFor("wf-e", 10).get(0);
        assertEquals(false, execution.actionResults().get(0).get("success"));
    }

    // ── Hardening: idempotency, edge detection, auto-clear (added 2026-08-14) ──

    @Test
    void theSameEventIdDeliveredTwiceProducesExactlyOneExecutionAndOneCommand() {
        ruleStore.save(compoundLeakWorkflow("wf-dedup"));
        CabinEvent event = leakEvent("z2m-leak_mech_room");

        service.evaluate(event);
        service.evaluate(event); // simulates a Kafka redelivery of the identical record

        assertEquals(1, adapter.commands.size(), "the physical command must not repeat on replay");
        assertEquals(1, eventPublisher.published.size());
        assertEquals(1, executionStore.recentFor("wf-dedup", 10).size());
    }

    @Test
    void aRepeatedTrueReadingWhileAlreadyActiveDoesNotReFire() {
        ruleStore.save(compoundLeakWorkflow("wf-edge"));

        service.evaluate(leakEvent("z2m-leak_mech_room")); // first real detection
        adapter.commands.clear();
        eventPublisher.published.clear();
        service.evaluate(leakEvent("z2m-leak_mech_room")); // a second, distinct event -- e.g. a routine heartbeat, still wet

        assertTrue(adapter.commands.isEmpty(), "a workflow already active must not re-fire its actions");
        assertTrue(eventPublisher.published.isEmpty());
        assertEquals(1, executionStore.recentFor("wf-edge", 10).size(), "still exactly one execution, not two");
    }

    @Test
    void aClearingReadingAutoClearsAnActiveAutoOnClearExecution() {
        ruleStore.save(compoundLeakWorkflow("wf-autoclear"));
        service.evaluate(leakEvent("z2m-leak_mech_room"));
        assertTrue(executionStore.findActive("wf-autoclear").isPresent());

        service.evaluate(leakClearedEvent("z2m-leak_mech_room"));

        Optional<WorkflowExecution> active = executionStore.findActive("wf-autoclear");
        assertTrue(active.isEmpty(), "the execution must no longer read as active once cleared");
        WorkflowExecution cleared = executionStore.recentFor("wf-autoclear", 1).get(0);
        assertNotNull(cleared.clearedAt());
        assertEquals("AUTO", cleared.clearedBy());
    }

    @Test
    void manualOnlyResetModeDoesNotAutoClear() {
        ruleStore.save(new WorkflowRule(
            "wf-manual-reset", "Strict shutoff", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "MANUAL_ONLY", null, Instant.now(), "test",
            List.of(new WorkflowAction("mr1", "wf-manual-reset", 0, "notify_critical", null, Map.of()))));
        service.evaluate(leakEvent("z2m-leak_mech_room"));

        service.evaluate(leakClearedEvent("z2m-leak_mech_room"));

        assertTrue(executionStore.findActive("wf-manual-reset").isPresent(),
            "MANUAL_ONLY workflows require a human POST .../clear -- a sensor reading alone must never clear them");
    }

    @Test
    void aFreshLeakAfterAutoClearFiresAgain() {
        ruleStore.save(compoundLeakWorkflow("wf-refire"));
        service.evaluate(leakEvent("z2m-leak_mech_room"));
        service.evaluate(leakClearedEvent("z2m-leak_mech_room"));
        adapter.commands.clear();
        eventPublisher.published.clear();

        service.evaluate(leakEvent("z2m-leak_mech_room")); // a genuinely new leak

        assertEquals(1, adapter.commands.size(), "clearing must not permanently disable the workflow");
        assertEquals(2, executionStore.recentFor("wf-refire", 10).size());
    }

    // ── Hardening: command confirmation states (added 2026-08-14) ──

    @Test
    void commandBecomesConfirmedOnceTheDeviceReportsTheExpectedState() throws InterruptedException {
        ruleStore.save(compoundLeakWorkflow("wf-confirm"));

        service.evaluate(leakEvent("z2m-leak_mech_room"));
        // Simulates Zigbee2MqttAdapter.handleDeviceState() processing the
        // device's own report -- real code path in production, called
        // directly here since there's no real MQTT broker in this test.
        deviceRegistry.update(new DeviceStatus("z2m-main_water_valve", DeviceType.HOME_ASSISTANT_ENTITY,
            "Main Water Valve", "OFFLINE", Instant.now(), Map.of("state", "OFF"), "cabin"));

        String executionId = executionStore.recentFor("wf-confirm", 1).get(0).executionId();
        String status = awaitCommandStatus(executionId, "wf-confirm-a2", 2000);

        assertEquals("CONFIRMED", status);
    }

    @Test
    void commandGoesUnconfirmedAndPublishesACriticalAlertWhenTheDeviceNeverMatches() throws InterruptedException {
        ruleStore.save(compoundLeakWorkflow("wf-unconfirmed"));

        service.evaluate(leakEvent("z2m-leak_mech_room"));
        // Deliberately do NOT update the device's reported state -- simulates
        // exactly what was found live 2026-08-14: a competing automation (or
        // any other cause) silently reverting the command.

        String executionId = executionStore.recentFor("wf-unconfirmed", 1).get(0).executionId();
        String status = awaitCommandStatus(executionId, "wf-unconfirmed-a2", 2000);

        assertEquals("UNCONFIRMED", status);
        boolean sawUnconfirmedAlert = eventPublisher.published.stream()
            .anyMatch(e -> "WORKFLOW_UNCONFIRMED".equals(e.eventType()) && "CRITICAL".equals(e.severity()));
        assertTrue(sawUnconfirmedAlert, "an UNCONFIRMED command must raise its own CRITICAL alert, not fail silently");
    }

    /** Polls executionStore the same way EventPipelineIntegrationTest's awaitEvent()/awaitAutomationAlert() do -- confirmation runs on a background scheduler, not synchronously inside evaluate(). */
    private String awaitCommandStatus(String executionId, String actionId, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Optional<WorkflowExecution> exec = executionStore.findById(executionId);
            if (exec.isPresent()) {
                for (Map<String, Object> result : exec.get().actionResults()) {
                    if (actionId.equals(result.get("actionId"))) {
                        String status = (String) result.get("commandStatus");
                        if ("CONFIRMED".equals(status) || "UNCONFIRMED".equals(status)) {
                            return status;
                        }
                    }
                }
            }
            Thread.sleep(20);
        }
        return "TIMED_OUT_WAITING";
    }

    // ── Camera detection trigger + generalized notification text (added 2026-08-18) ──

    private CabinEvent cameraDetectionEvent(String cameraId, String label, double score) {
        return new CabinEvent(UUID.randomUUID().toString(), cameraId, "DETECTION_NEW", "INFO",
            Instant.now(), Map.of("label", label, "score", score, "hasClip", true));
    }

    private WorkflowRule notifyOnlyCameraWorkflow(String workflowId, String cameraId) {
        return new WorkflowRule(workflowId, "Notify: driveway", "cabin", "DEVICE_EVENT",
            "trigger_camera_detection", cameraId, true, "MANUAL_ONLY", null, Instant.now(), "test",
            List.of(new WorkflowAction(workflowId + "-a1", workflowId, 0, "notify_critical", null, Map.of())));
    }

    @Test
    void aNewCameraDetectionFiresAMatchingWorkflow() {
        ruleStore.save(notifyOnlyCameraWorkflow("wf-cam", "driveway"));

        service.evaluate(cameraDetectionEvent("driveway", "person", 0.83));

        assertEquals(1, eventPublisher.published.size());
        assertEquals("WORKFLOW_ACTION", eventPublisher.published.get(0).eventType());
        assertEquals(1, executionStore.recentFor("wf-cam", 10).size());
    }

    @Test
    void detectionUpdateAndEndAndPlainMotionDoNotMatchTheCameraTrigger() {
        ruleStore.save(notifyOnlyCameraWorkflow("wf-cam-2", "driveway"));

        service.evaluate(new CabinEvent(UUID.randomUUID().toString(), "driveway", "DETECTION_UPDATE", "INFO",
            Instant.now(), Map.of("label", "person", "score", 0.9)));
        service.evaluate(new CabinEvent(UUID.randomUUID().toString(), "driveway", "DETECTION_END", "INFO",
            Instant.now(), Map.of("label", "person", "score", 0.9)));
        service.evaluate(new CabinEvent(UUID.randomUUID().toString(), "driveway", "MOTION_ON", "INFO",
            Instant.now(), Map.of("camera", "driveway")));

        assertTrue(eventPublisher.published.isEmpty(), "only DETECTION_NEW should match trigger_camera_detection");
        assertTrue(executionStore.recentFor("wf-cam-2", 10).isEmpty());
    }

    @Test
    void aCameraWorkflowScopedToOneCameraIgnoresDetectionsFromAnother() {
        ruleStore.save(notifyOnlyCameraWorkflow("wf-cam-3", "driveway"));

        service.evaluate(cameraDetectionEvent("home_aldrich_front", "person", 0.7));

        assertTrue(eventPublisher.published.isEmpty());
        assertTrue(executionStore.recentFor("wf-cam-3", 10).isEmpty());
    }

    @Test
    void aCameraWorkflowSelfClearsSoItFiresAgainOnTheNextDetection() {
        ruleStore.save(notifyOnlyCameraWorkflow("wf-cam-repeat", "driveway"));

        service.evaluate(cameraDetectionEvent("driveway", "person", 0.83));
        service.evaluate(cameraDetectionEvent("driveway", "person", 0.91)); // a second, later, distinct detection

        assertEquals(2, eventPublisher.published.size(),
            "unlike the sticky leak workflow, a camera-detection workflow must not get stuck 'active' after firing once");
        assertEquals(2, executionStore.recentFor("wf-cam-repeat", 10).size());
        assertNotNull(executionStore.recentFor("wf-cam-repeat", 10).get(0).clearedAt(),
            "a camera-detection execution self-clears immediately (clearedBy=AUTO)");
        assertEquals("AUTO", executionStore.recentFor("wf-cam-repeat", 10).get(0).clearedBy());
    }

    @Test
    void aCompoundLeakWorkflowStillStaysActiveUntilClearedRegardlessOfActionList() {
        // Regression guard for the self-clear rework above: this must stay
        // scoped to the TRIGGER (water_leak has a symmetric clear signal,
        // camera detection doesn't), never to whether the action list
        // happens to be all-notify -- manualOnlyResetModeDoesNotAutoClear
        // above already covers a notify-only leak workflow's behavior
        // end-to-end; this asserts the same clearedAt/clearedBy fact
        // directly for the compound (notify + command) case too.
        ruleStore.save(compoundLeakWorkflow("wf-stays-active"));

        service.evaluate(leakEvent("z2m-leak_mech_room"));

        WorkflowExecution execution = executionStore.recentFor("wf-stays-active", 10).get(0);
        assertNull(execution.clearedAt());
        assertNull(execution.clearedBy());
    }

    @Test
    void notificationTextDescribesTheActualCameraDetectionNotAHardcodedLeakMessage() {
        ruleStore.save(notifyOnlyCameraWorkflow("wf-cam-text", "driveway"));

        service.evaluate(cameraDetectionEvent("driveway", "person", 0.83));

        Map<String, Object> payload = eventPublisher.published.get(0).payload();
        assertEquals("driveway detected person (83%)", payload.get("see"));
        assertEquals("Notify", payload.get("act"));
        assertTrue(String.valueOf(payload.get("think")).contains("Notify: driveway"));
    }

    @Test
    void notificationTextForTheLeakWorkflowIsUnchangedByTheGeneralization() {
        ruleStore.save(compoundLeakWorkflow("wf-leak-text"));

        service.evaluate(leakEvent("z2m-leak_mech_room"));

        Map<String, Object> payload = eventPublisher.published.get(0).payload();
        assertEquals("Water leak detected", payload.get("see"));
        assertEquals("Notify + Shut off main water valve", payload.get("act"));
    }
}
