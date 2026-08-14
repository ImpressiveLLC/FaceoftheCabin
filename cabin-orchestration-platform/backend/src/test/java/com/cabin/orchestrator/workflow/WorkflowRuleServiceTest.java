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

    @Test
    void compoundWorkflowRunsNotifyAndValveOffInOrderAndRecordsAnExecution() {
        ruleStore.save(new WorkflowRule(
            "wf-compound", "Leak shutoff", "cabin", "DEVICE_EVENT", "trigger_water_leak_detected", null,
            true, "AUTO_ON_CLEAR", null, Instant.now(), "test",
            List.of(
                new WorkflowAction("a1", "wf-compound", 0, "notify_critical", null, Map.of()),
                new WorkflowAction("a2", "wf-compound", 1, "action_main_water_valve_off",
                    "z2m-main_water_valve", Map.of()))));

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
}
