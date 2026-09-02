package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.ontology.OntologyLookupService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.workflow.JdbcWorkflowExecutionStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowRuleStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowVocabularyStore;
import com.cabin.orchestrator.workflow.WorkflowActionTargetValidator;
import com.cabin.orchestrator.workflow.WorkflowRuleService;
import com.cabin.orchestrator.workflow.model.ActionVocabularyEntry;
import com.cabin.orchestrator.workflow.model.TriggerVocabularyEntry;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.cabin.orchestrator.workflow.model.WorkflowHealth;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Human-configured workflow rule CRUD + execution history/view-tracking.
 *
 * Hardened 2026-08-14 (external review of the first live test): GET
 * endpoints stay open (matches how every other read-only resource in this
 * app works -- see WebConfig's own doc comment), but every write here goes
 * through GoogleAuthInterceptor (added to WebConfig's addPathPatterns) --
 * unlike a one-off manual device command, a workflow is a standing,
 * automatically-firing mechanism against real actuators, so it gets the
 * same write gate as notes/chores/profiles, not the device-status
 * endpoints' open-by-default posture. Actor identity (createdBy, clearedBy)
 * is always derived server-side from the verified token
 * (GoogleAuthInterceptor.REQUEST_ATTR_EMAIL), never trusted from the
 * request body, same convention TechIdController already uses.
 *
 * New workflows are always created disabled ("draft") regardless of what
 * the request body claims -- activation is a separate, explicit, equally
 * authenticated call. A workflow whose trigger fires from a real device
 * event (triggerKind=DEVICE_EVENT) may never contain
 * action_main_water_valve_open in its action list -- reopening the main
 * valve is a privileged, human-only action, enforced here rather than left
 * as an ontology convention someone could accidentally violate via this
 * raw API. (v1: this specific action id is hardcoded, matching
 * WorkflowRuleService's own "vocabulary isn't ontology-driven yet" scope
 * note -- a real ontology-flag-driven version of this guard is follow-up
 * work once that vocabulary exists.)
 *
 * GET .../vocabulary/triggers and .../vocabulary/actions (added
 * 2026-08-21, the path this class's own 2026-08-14 comment reserved for
 * "Codex's parallel slice") give WorkflowCreateForm a real, ontology-
 * traceable catalog to render instead of the hardcoded JS lists it used
 * before -- see JdbcWorkflowVocabularyStore's own doc for the full design
 * (seeded/supported rows own the DB table, candidate/unsupported rows are
 * merged in live from docs/ontology.yaml via OntologyLookupService, and
 * none of it is ever writable through this or any other endpoint).
 */
@RestController
@RequestMapping("/api/rules")
@CrossOrigin
public class RulesController {

    private static final String PRIVILEGED_REOPEN_ACTION = "action_main_water_valve_open";

    private final JdbcWorkflowRuleStore ruleStore;
    private final JdbcWorkflowExecutionStore executionStore;
    private final WorkflowRuleService workflowRuleService;
    private final JdbcWorkflowVocabularyStore vocabularyStore;
    private final OntologyLookupService ontologyLookupService;
    private final WorkflowActionTargetValidator targetValidator;
    private final DeviceHealthMonitor healthMonitor;

    public RulesController(JdbcWorkflowRuleStore ruleStore, JdbcWorkflowExecutionStore executionStore,
                            WorkflowRuleService workflowRuleService, JdbcWorkflowVocabularyStore vocabularyStore,
                            OntologyLookupService ontologyLookupService, WorkflowActionTargetValidator targetValidator,
                            DeviceHealthMonitor healthMonitor) {
        this.ruleStore = ruleStore;
        this.executionStore = executionStore;
        this.workflowRuleService = workflowRuleService;
        this.vocabularyStore = vocabularyStore;
        this.ontologyLookupService = ontologyLookupService;
        this.targetValidator = targetValidator;
        this.healthMonitor = healthMonitor;
    }

    /** Supported (seeded, DB-owned) triggers plus candidate ones merged live from docs/ontology.yaml -- see class doc. */
    @GetMapping("/vocabulary/triggers")
    public List<TriggerVocabularyEntry> triggerVocabulary() {
        List<TriggerVocabularyEntry> supported = vocabularyStore.loadSupportedTriggers();
        Set<String> supportedIds = supported.stream().map(TriggerVocabularyEntry::id).collect(Collectors.toSet());
        List<TriggerVocabularyEntry> all = new ArrayList<>(supported);
        all.addAll(ontologyLookupService.listCandidateTriggers(supportedIds));
        return all;
    }

    /** The action-side counterpart to triggerVocabulary() -- see that method's own doc. */
    @GetMapping("/vocabulary/actions")
    public List<ActionVocabularyEntry> actionVocabulary() {
        List<ActionVocabularyEntry> supported = vocabularyStore.loadSupportedActions();
        Set<String> supportedIds = supported.stream().map(ActionVocabularyEntry::id).collect(Collectors.toSet());
        List<ActionVocabularyEntry> all = new ArrayList<>(supported);
        all.addAll(ontologyLookupService.listCandidateActions(supportedIds));
        return all;
    }

    /**
     * health is additive -- @JsonUnwrapped keeps every WorkflowRule field a
     * flat sibling in the same JSON object, so existing consumers reading
     * e.g. workflow.workflowId/workflow.actions see no shape change at all.
     * See WorkflowHealth's own doc for BROKEN/DEGRADED/HEALTHY.
     */
    @GetMapping("/workflows")
    public List<WorkflowRuleView> listWorkflows() {
        Map<String, CheckinStatus> checkinStatuses = healthMonitor.getCheckinStatuses();
        return ruleStore.loadAll().stream()
            .map(rule -> new WorkflowRuleView(rule, WorkflowHealth.of(targetValidator.health(rule.actions(), checkinStatuses))))
            .toList();
    }

    public record WorkflowRuleView(@JsonUnwrapped WorkflowRule rule, WorkflowHealth health) {}

    @PostMapping("/workflows")
    public Map<String, Object> createWorkflow(@RequestBody WorkflowRule rule, HttpServletRequest request) {
        String violation = validateReopenGuard(rule);
        if (violation != null) return Map.of("error", violation);
        violation = validateActionTargets(rule);
        if (violation != null) return Map.of("error", violation);

        String actor = actorEmail(request);
        WorkflowRule draft = new WorkflowRule(
            rule.workflowId(), rule.name(), rule.location(), rule.triggerKind(),
            rule.triggerDefinitionId(), rule.triggerDeviceId(),
            false, // always created disabled -- explicit /activate call required
            rule.resetMode(), rule.parentWorkflowId(), Instant.now(), actor, rule.actions());
        ruleStore.save(draft);
        return Map.of("workflowId", draft.workflowId(), "enabled", false);
    }

    /** The only way a workflow becomes live -- separate, authenticated, explicit. */
    @PostMapping("/workflows/{id}/activate")
    public Map<String, Object> activateWorkflow(@PathVariable String id) {
        Optional<WorkflowRule> existing = ruleStore.findById(id);
        if (existing.isEmpty()) return Map.of("error", "not found");
        WorkflowRule rule = existing.get();
        String violation = validateReopenGuard(rule);
        if (violation != null) return Map.of("error", violation);
        violation = validateActionTargets(rule);
        if (violation != null) return Map.of("error", violation);
        ruleStore.save(new WorkflowRule(
            rule.workflowId(), rule.name(), rule.location(), rule.triggerKind(),
            rule.triggerDefinitionId(), rule.triggerDeviceId(), true, rule.resetMode(),
            rule.parentWorkflowId(), rule.createdAt(), rule.createdBy(), rule.actions()));
        return Map.of("workflowId", id, "enabled", true);
    }

    @PostMapping("/workflows/{id}/deactivate")
    public Map<String, Object> deactivateWorkflow(@PathVariable String id) {
        Optional<WorkflowRule> existing = ruleStore.findById(id);
        if (existing.isEmpty()) return Map.of("error", "not found");
        WorkflowRule rule = existing.get();
        ruleStore.save(new WorkflowRule(
            rule.workflowId(), rule.name(), rule.location(), rule.triggerKind(),
            rule.triggerDefinitionId(), rule.triggerDeviceId(), false, rule.resetMode(),
            rule.parentWorkflowId(), rule.createdAt(), rule.createdBy(), rule.actions()));
        return Map.of("workflowId", id, "enabled", false);
    }

    @GetMapping("/workflows/{id}")
    public Map<String, Object> getWorkflow(@PathVariable String id) {
        return ruleStore.findById(id)
            .<Map<String, Object>>map(r -> Map.of("workflow", r))
            .orElseGet(() -> Map.of("error", "not found"));
    }

    @DeleteMapping("/workflows/{id}")
    public Map<String, Object> deleteWorkflow(@PathVariable String id) {
        ruleStore.delete(id);
        return Map.of("workflowId", id, "deleted", true);
    }

    @GetMapping("/workflows/{id}/executions")
    public List<WorkflowExecution> executions(@PathVariable String id,
                                               @RequestParam(defaultValue = "20") int limit) {
        return executionStore.recentFor(id, limit);
    }

    @GetMapping("/executions/recent")
    public List<WorkflowExecution> recentUnviewed() {
        return executionStore.findUnviewed();
    }

    @PostMapping("/executions/{id}/view")
    public Map<String, Object> markViewed(@PathVariable String id) {
        Optional<WorkflowExecution> existing = executionStore.findById(id);
        if (existing.isEmpty()) return Map.of("error", "not found");
        WorkflowExecution e = existing.get();
        executionStore.save(new WorkflowExecution(
            e.executionId(), e.workflowId(), e.triggeredByEventId(), e.firedAt(),
            e.clearedAt(), e.clearedBy(), e.actionResults(), Instant.now()));
        return Map.of("executionId", id, "viewed", true);
    }

    @PostMapping("/executions/{id}/clear")
    public Map<String, Object> clearExecution(@PathVariable String id, HttpServletRequest request) {
        Optional<WorkflowExecution> existing = executionStore.findById(id);
        if (existing.isEmpty()) return Map.of("error", "not found");
        WorkflowExecution e = existing.get();
        String clearedBy = "HUMAN:" + actorEmail(request);
        executionStore.save(new WorkflowExecution(
            e.executionId(), e.workflowId(), e.triggeredByEventId(), e.firedAt(),
            Instant.now(), clearedBy, e.actionResults(), e.lastViewedAt()));
        return Map.of("executionId", id, "cleared", true);
    }

    /**
     * MANUAL trigger_kind workflows only (e.g. the "Reopen valve" layered
     * example) -- see WorkflowRuleService, which never auto-fires these.
     * Implemented 2026-08-21 (was a stub until now, deliberately: "reopen
     * ships only after the close path is fully proven, per the reviewed
     * plan"): now calls WorkflowRuleService.fireManual(), which records a
     * new execution with no source CabinEvent (triggeredByEventId=null --
     * every tap creates a new execution, matching
     * JdbcWorkflowExecutionStore's own dedup-index comment about MANUAL
     * fires). validateReopenGuard() above still keeps
     * action_main_water_valve_open out of every DEVICE_EVENT-triggered
     * workflow -- this endpoint is the only legitimate way that action
     * ever runs.
     */
    @PostMapping("/workflows/{id}/fire")
    public Map<String, Object> fireManual(@PathVariable String id, HttpServletRequest request) {
        Optional<WorkflowRule> existing = ruleStore.findById(id);
        if (existing.isEmpty()) return Map.of("error", "not found");
        WorkflowRule rule = existing.get();
        if (!"MANUAL".equals(rule.triggerKind())) {
            return Map.of("error", "Only MANUAL trigger_kind workflows can be fired via this endpoint");
        }
        if (!rule.enabled()) {
            return Map.of("error", "Workflow is not active -- activate it first");
        }
        WorkflowExecution execution = workflowRuleService.fireManual(rule, actorEmail(request));
        return Map.of("executionId", execution.executionId(), "fired", true);
    }

    /**
     * The supervised, validated replacement for editing Postgres directly to
     * fix a workflow whose target device is gone/reassigned -- closes a real
     * gap (there was previously no way to edit an existing workflow's action
     * target at all). Deliberately allowed even for an instance-locked
     * action (e.g. the main valve) -- an admin choosing a genuine
     * replacement device here is exactly the coached alternative
     * WorkflowCreateForm's own "Change target device" override exists for,
     * not a way around that lock's purpose.
     */
    @PostMapping("/workflows/{id}/actions/{actionId}/retarget")
    public Map<String, Object> retargetAction(@PathVariable String id, @PathVariable String actionId,
                                               @RequestBody Map<String, String> body) {
        Optional<WorkflowRule> existing = ruleStore.findById(id);
        if (existing.isEmpty()) return Map.of("error", "not found");
        WorkflowRule rule = existing.get();
        String newTargetDeviceId = body.get("targetDeviceId");

        boolean actionFound = false;
        List<WorkflowAction> updatedActions = new ArrayList<>();
        for (WorkflowAction action : rule.actions()) {
            if (action.actionId().equals(actionId)) {
                actionFound = true;
                updatedActions.add(new WorkflowAction(
                    action.actionId(), action.workflowId(), action.stepOrder(), action.actionDefinitionId(),
                    newTargetDeviceId, action.actionConfig(), action.cooldownSeconds()));
            } else {
                updatedActions.add(action);
            }
        }
        if (!actionFound) return Map.of("error", "Action " + actionId + " not found on workflow " + id);

        WorkflowRule updated = new WorkflowRule(
            rule.workflowId(), rule.name(), rule.location(), rule.triggerKind(),
            rule.triggerDefinitionId(), rule.triggerDeviceId(), rule.enabled(), rule.resetMode(),
            rule.parentWorkflowId(), rule.createdAt(), rule.createdBy(), updatedActions);

        String violation = validateActionTargets(updated);
        if (violation != null) return Map.of("error", violation);

        ruleStore.save(updated);
        return Map.of("workflowId", id, "actionId", actionId, "targetDeviceId", newTargetDeviceId);
    }

    /** Real targetDeviceId validation -- see WorkflowActionTargetValidator's own doc. */
    private String validateActionTargets(WorkflowRule rule) {
        return targetValidator.validate(rule.actions());
    }

    private String validateReopenGuard(WorkflowRule rule) {
        if (!"DEVICE_EVENT".equals(rule.triggerKind())) return null;
        boolean hasPrivilegedReopen = rule.actions().stream()
            .map(WorkflowAction::actionDefinitionId)
            .anyMatch(PRIVILEGED_REOPEN_ACTION::equals);
        if (hasPrivilegedReopen) {
            return "action_main_water_valve_open cannot be attached to a DEVICE_EVENT trigger -- reopening the main valve is human-only, fire it via a MANUAL workflow instead";
        }
        return null;
    }

    private String actorEmail(HttpServletRequest request) {
        Object email = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        return email != null ? email.toString() : "unknown";
    }
}
