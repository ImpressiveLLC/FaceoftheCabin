package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * v1 (2026-08-14): trigger matching is a small hardcoded lookup table from
 * CabinEvent shape -> trigger_definition id, matching exactly the flagship
 * trigger_definition entities shipped alongside this class
 * (docs/ontology.yaml's trigger_water_leak_detected). A real ontology-
 * driven vocabulary lookup (via OntologyLookupService) replaces
 * resolveTriggerDefinitionId() once that vocabulary exists -- tracked as
 * real follow-up, not silently permanent. See the "Human-Driven,
 * Ontology-Exclusive Rules & Alerts Engine" plan for the full design.
 */
@Service
public class WorkflowRuleService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuleService.class);

    private final JdbcWorkflowRuleStore ruleStore;
    private final JdbcWorkflowExecutionStore executionStore;
    private final DeviceRegistry deviceRegistry;
    private final CommandCatalogService commandCatalog;
    private final EventPublisher eventPublisher;

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
        if (triggerId == null) return;

        List<WorkflowRule> matches = ruleStore.findByTrigger(triggerId, event.sourceDeviceId());
        for (WorkflowRule rule : matches) {
            fire(rule, event);
        }
    }

    private String resolveTriggerDefinitionId(CabinEvent event) {
        if ("TELEMETRY".equals(event.eventType()) && Boolean.TRUE.equals(event.payload().get("water_leak"))) {
            return "trigger_water_leak_detected";
        }
        return null;
    }

    private void fire(WorkflowRule rule, CabinEvent triggeringEvent) {
        log.info("Workflow '{}' ({}) firing on event {}", rule.name(), rule.workflowId(), triggeringEvent.eventId());
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkflowAction action : rule.actions()) {
            results.add(executeAction(action, rule, triggeringEvent));
        }
        WorkflowExecution execution = new WorkflowExecution(
            UUID.randomUUID().toString(), rule.workflowId(), triggeringEvent.eventId(),
            Instant.now(), null, null, results, null);
        executionStore.save(execution);
    }

    private Map<String, Object> executeAction(WorkflowAction action, WorkflowRule rule, CabinEvent triggeringEvent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionId", action.actionId());
        result.put("actionDefinitionId", action.actionDefinitionId());
        try {
            switch (action.actionDefinitionId()) {
                case "action_main_water_valve_off" -> executeValveCommand(action, "OFF", result);
                case "action_main_water_valve_open" -> executeValveCommand(action, "ON", result);
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

    private void executeValveCommand(WorkflowAction action, String state, Map<String, Object> result) {
        String deviceId = action.targetDeviceId();
        if (!commandCatalog.isCommandAllowed(deviceId)) {
            result.put("success", false);
            result.put("error", "Device " + deviceId + " is not command-capable or not found");
            return;
        }
        boolean ok = deviceRegistry.sendCommand(deviceId, "state.set", Map.of("state", state));
        result.put("success", ok);
        result.put("deviceId", deviceId);
        result.put("commandedState", state);
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
}
