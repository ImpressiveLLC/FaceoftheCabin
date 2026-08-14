package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The human-configured workflow interpreter. Reads workflow_rule rows (via
 * JdbcWorkflowRuleStore) and matches them against every incoming CabinEvent
 * -- the same event AutomationRuleService already evaluates, hooked in
 * right after it in EventConsumer.pollLoop(), not a parallel pipeline.
 *
 * Compound (ordered actions on one workflow_rule) vs. parallel/layered (a
 * second, independent workflow_rule sharing the same trigger, e.g. a human-
 * added "reopen valve" workflow) are both just "iterate every matching row"
 * here -- neither blocks or depends on the other succeeding. A failed
 * action never skips the rest of its own workflow's action list; each
 * result is recorded independently in the execution's actionResults.
 *
 * Hardened 2026-08-14 after an external review of the first live test found
 * three real gaps, all fixed here:
 * 1. Idempotency -- a Kafka redelivery of the same source event must not
 *    re-fire a physical action. Guarded two ways: a check-before-execute
 *    against JdbcWorkflowExecutionStore.existsForTriggerEvent() (so a
 *    duplicate is caught BEFORE any command is sent, not just before the
 *    save), plus a DB-level unique index as defense in depth.
 * 2. Edge detection -- a leak sensor re-reporting the same water_leak:true
 *    (routine heartbeat, not a new event) must not re-fire a workflow
 *    that's already active. Guarded via findActive(): a workflow with an
 *    uncleared execution is skipped until it clears.
 * 3. Command confirmation -- sendCommand() returning true only proves the
 *    MQTT publish succeeded, not that the physical device moved (found
 *    live: a competing HA automation silently reverted a reopen command
 *    within the same second). Command-kind actions now go ACCEPTED ->
 *    (scheduled re-check) -> CONFIRMED or UNCONFIRMED, matching the
 *    confirmation pattern CabinAutomations#1 already uses for the same
 *    device. UNCONFIRMED publishes its own CRITICAL notification.
 *
 * v1 (2026-08-14): trigger matching is a small hardcoded lookup table from
 * CabinEvent shape -> trigger_definition id, matching exactly the flagship
 * trigger_definition entities in docs/ontology.yaml. A real ontology-driven
 * vocabulary lookup (via OntologyLookupService) replaces
 * resolveTriggerDefinitionId()/resolveClearedTriggerDefinitionId() once
 * that vocabulary exists -- tracked as real follow-up, not silently
 * permanent.
 */
@Service
public class WorkflowRuleService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuleService.class);

    // Milliseconds, not seconds -- lets tests override these to tens of
    // milliseconds via ReflectionTestUtils (same @Value-outside-Spring
    // pattern AutomationRuleServiceTest already uses) instead of a test
    // suite actually waiting 10-30 real seconds per confirmation check.
    @Value("${cabin.workflow.confirmation.initialDelayMillis:10000}")
    private long confirmationInitialDelayMillis;

    @Value("${cabin.workflow.confirmation.finalDelayMillis:30000}")
    private long confirmationFinalDelayMillis;

    private final JdbcWorkflowRuleStore ruleStore;
    private final JdbcWorkflowExecutionStore executionStore;
    private final DeviceRegistry deviceRegistry;
    private final CommandCatalogService commandCatalog;
    private final EventPublisher eventPublisher;
    private final ScheduledExecutorService confirmationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "workflow-command-confirm");
        t.setDaemon(true);
        return t;
    });

    public WorkflowRuleService(JdbcWorkflowRuleStore ruleStore, JdbcWorkflowExecutionStore executionStore,
                                DeviceRegistry deviceRegistry, CommandCatalogService commandCatalog,
                                EventPublisher eventPublisher) {
        this.ruleStore = ruleStore;
        this.executionStore = executionStore;
        this.deviceRegistry = deviceRegistry;
        this.commandCatalog = commandCatalog;
        this.eventPublisher = eventPublisher;
    }

    public void evaluate(CabinEvent event) {
        // This class's own WORKFLOW_ACTION output loops back through this
        // same consumer -- never re-match it (same guard shape as
        // AutomationRuleService's own default no-op case for AUTOMATION_ALERT).
        if (event.eventType().startsWith("WORKFLOW_")) return;

        String triggerId = resolveTriggerDefinitionId(event);
        if (triggerId != null) {
            handleTriggerMatch(triggerId, event);
            return;
        }
        String clearedTriggerId = resolveClearedTriggerDefinitionId(event);
        if (clearedTriggerId != null) {
            handleTriggerCleared(clearedTriggerId, event);
        }
    }

    private String resolveTriggerDefinitionId(CabinEvent event) {
        if ("TELEMETRY".equals(event.eventType()) && Boolean.TRUE.equals(event.payload().get("water_leak"))) {
            return "trigger_water_leak_detected";
        }
        return null;
    }

    /** The inverse of resolveTriggerDefinitionId() -- an explicit false, not merely absent, is what counts as "cleared." */
    private String resolveClearedTriggerDefinitionId(CabinEvent event) {
        if ("TELEMETRY".equals(event.eventType()) && Boolean.FALSE.equals(event.payload().get("water_leak"))) {
            return "trigger_water_leak_detected";
        }
        return null;
    }

    private void handleTriggerMatch(String triggerId, CabinEvent event) {
        List<WorkflowRule> matches = ruleStore.findByTrigger(triggerId, event.sourceDeviceId());
        for (WorkflowRule rule : matches) {
            if (executionStore.existsForTriggerEvent(rule.workflowId(), event.eventId())) {
                log.debug("Workflow '{}' already has an execution for event {}, skipping (idempotency)",
                    rule.workflowId(), event.eventId());
                continue;
            }
            if (executionStore.findActive(rule.workflowId()).isPresent()) {
                log.debug("Workflow '{}' already has an active execution, skipping re-fire (edge detection)",
                    rule.workflowId());
                continue;
            }
            fire(rule, event);
        }
    }

    /** Auto-clears any active execution for a matching AUTO_ON_CLEAR workflow -- a human can always also clear manually via POST .../clear regardless of resetMode. */
    private void handleTriggerCleared(String triggerId, CabinEvent event) {
        List<WorkflowRule> matches = ruleStore.findByTrigger(triggerId, event.sourceDeviceId());
        for (WorkflowRule rule : matches) {
            if (!"AUTO_ON_CLEAR".equals(rule.resetMode())) continue;
            executionStore.findActive(rule.workflowId()).ifPresent(exec -> {
                log.info("Workflow '{}' trigger condition cleared by event {}, auto-clearing execution {}",
                    rule.workflowId(), event.eventId(), exec.executionId());
                executionStore.save(new WorkflowExecution(
                    exec.executionId(), exec.workflowId(), exec.triggeredByEventId(), exec.firedAt(),
                    Instant.now(), "AUTO", exec.actionResults(), exec.lastViewedAt()));
            });
        }
    }

    private void fire(WorkflowRule rule, CabinEvent triggeringEvent) {
        log.info("Workflow '{}' ({}) firing on event {}", rule.name(), rule.workflowId(), triggeringEvent.eventId());
        String executionId = UUID.randomUUID().toString();
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkflowAction action : rule.actions()) {
            results.add(executeAction(executionId, action, rule, triggeringEvent));
        }
        WorkflowExecution execution = new WorkflowExecution(
            executionId, rule.workflowId(), triggeringEvent.eventId(),
            Instant.now(), null, null, results, null);
        executionStore.save(execution);
    }

    private Map<String, Object> executeAction(String executionId, WorkflowAction action, WorkflowRule rule,
                                               CabinEvent triggeringEvent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionId", action.actionId());
        result.put("actionDefinitionId", action.actionDefinitionId());
        try {
            switch (action.actionDefinitionId()) {
                case "action_main_water_valve_off" -> executeValveCommand(executionId, action, "OFF", result);
                case "action_main_water_valve_open" -> executeValveCommand(executionId, action, "ON", result);
                case "log_event" ->
                    // Logging is automatic -- CabinEventService already persists
                    // every event this engine reacts to and every WORKFLOW_ACTION
                    // event it produces. This action kind exists purely so a
                    // human sees "log" as an explicit selected step.
                    result.put("success", true);
                case "notify_critical" -> {
                    publishNotification(rule, triggeringEvent);
                    result.put("success", true);
                }
                default -> {
                    result.put("success", false);
                    result.put("error", "Unknown actionDefinitionId: " + action.actionDefinitionId());
                }
            }
        } catch (Exception e) {
            log.warn("Workflow action {} failed: {}", action.actionId(), e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Issues the command and marks it ACCEPTED (the MQTT publish succeeded)
     * -- NOT proof the device actually moved. Schedules an async re-check
     * that upgrades this same action's result to CONFIRMED/UNCONFIRMED once
     * the device's own reported state either matches or the timeout elapses.
     */
    private void executeValveCommand(String executionId, WorkflowAction action, String state, Map<String, Object> result) {
        String deviceId = action.targetDeviceId();
        if (!commandCatalog.isCommandAllowed(deviceId)) {
            result.put("success", false);
            result.put("commandStatus", "REJECTED");
            result.put("error", "Device " + deviceId + " is not command-capable or not found");
            return;
        }
        boolean published = deviceRegistry.sendCommand(deviceId, "state.set", Map.of("state", state));
        result.put("success", published);
        result.put("deviceId", deviceId);
        result.put("commandedState", state);
        result.put("commandStatus", published ? "ACCEPTED" : "PUBLISH_FAILED");
        if (published) {
            scheduleConfirmation(executionId, action.actionId(), deviceId, state, false);
        }
    }

    private void scheduleConfirmation(String executionId, String actionId, String deviceId, String expectedState,
                                       boolean isFinalAttempt) {
        long delay = isFinalAttempt
            ? Math.max(0, confirmationFinalDelayMillis - confirmationInitialDelayMillis)
            : confirmationInitialDelayMillis;
        confirmationScheduler.schedule(
            () -> checkConfirmation(executionId, actionId, deviceId, expectedState, isFinalAttempt),
            delay, TimeUnit.MILLISECONDS);
    }

    /** Package-private so tests can invoke this synchronously instead of waiting on the real scheduler. */
    void checkConfirmation(String executionId, String actionId, String deviceId, String expectedState,
                            boolean isFinalAttempt) {
        DeviceStatus current = deviceRegistry.get(deviceId);
        Object currentState = current == null ? null : current.attributes().get("state");
        boolean confirmed = expectedState.equalsIgnoreCase(String.valueOf(currentState));

        if (!confirmed && !isFinalAttempt) {
            scheduleConfirmation(executionId, actionId, deviceId, expectedState, true);
            return;
        }

        Optional<WorkflowExecution> existing = executionStore.findById(executionId);
        if (existing.isEmpty()) return; // execution was never saved or was somehow removed -- nothing to update
        WorkflowExecution exec = existing.get();
        List<Map<String, Object>> updatedResults = new ArrayList<>();
        for (Map<String, Object> r : exec.actionResults()) {
            if (actionId.equals(r.get("actionId"))) {
                Map<String, Object> updated = new LinkedHashMap<>(r);
                updated.put("commandStatus", confirmed ? "CONFIRMED" : "UNCONFIRMED");
                updatedResults.add(updated);
            } else {
                updatedResults.add(r);
            }
        }
        executionStore.save(new WorkflowExecution(
            exec.executionId(), exec.workflowId(), exec.triggeredByEventId(), exec.firedAt(),
            exec.clearedAt(), exec.clearedBy(), updatedResults, exec.lastViewedAt()));

        if (!confirmed) {
            log.warn("Command on {} (execution {}) UNCONFIRMED after {}ms -- device never reported the expected state",
                deviceId, executionId, confirmationFinalDelayMillis);
            publishUnconfirmedAlert(deviceId, expectedState, executionId);
        } else {
            log.info("Command on {} (execution {}) CONFIRMED", deviceId, executionId);
        }
    }

    private void publishUnconfirmedAlert(String deviceId, String expectedState, String executionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", "WORKFLOW_UNCONFIRMED_" + executionId);
        payload.put("see", "Commanded device did not confirm the expected state");
        payload.put("think", "Sent " + expectedState + " to " + deviceId + " but its reported state never matched within "
            + confirmationFinalDelayMillis + "ms");
        payload.put("act", "Check the device physically -- this command may not have taken effect");
        payload.put("tags", List.of("WORKFLOW", "UNCONFIRMED"));
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), deviceId, "WORKFLOW_UNCONFIRMED", "CRITICAL", Instant.now(), payload));
    }

    /** Reuses AutomationRuleService's exact see/think/act/tags payload shape so NtfyAlertPublisher/AutomationAlertCard need zero changes. */
    private void publishNotification(WorkflowRule rule, CabinEvent triggeringEvent) {
        Map<String, Object> payload = new LinkedHashMap<>(triggeringEvent.payload());
        payload.put("ruleId", "WORKFLOW_" + rule.workflowId());
        payload.put("see", "Water leak detected");
        payload.put("think", "Human-configured workflow '" + rule.name() + "' matched this event");
        payload.put("act", "Notify + shut off main water valve");
        payload.put("tags", List.of("WORKFLOW"));
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), triggeringEvent.sourceDeviceId(), "WORKFLOW_ACTION", "CRITICAL",
            Instant.now(), payload));
    }

    @PreDestroy
    public void shutdown() {
        confirmationScheduler.shutdownNow();
    }
}
