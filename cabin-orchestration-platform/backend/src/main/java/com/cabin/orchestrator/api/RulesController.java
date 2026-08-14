package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.workflow.JdbcWorkflowExecutionStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowRuleStore;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * v1 (2026-08-14): vocabulary lookup endpoints (GET .../vocabulary/*) are
 * NOT included here -- that's Codex's parallel slice (OntologyLookupService
 * reverse-lookup), landing separately.
 */
@RestController
@RequestMapping("/api/rules")
@CrossOrigin
public class RulesController {

    private static final String PRIVILEGED_REOPEN_ACTION = "action_main_water_valve_open";

    private final JdbcWorkflowRuleStore ruleStore;
    private final JdbcWorkflowExecutionStore executionStore;

    public RulesController(JdbcWorkflowRuleStore ruleStore, JdbcWorkflowExecutionStore executionStore) {
        this.ruleStore = ruleStore;
        this.executionStore = executionStore;
    }

    @GetMapping("/workflows")
    public List<WorkflowRule> listWorkflows() {
        return ruleStore.loadAll();
    }

    @PostMapping("/workflows")
    public Map<String, Object> createWorkflow(@RequestBody WorkflowRule rule, HttpServletRequest request) {
        String violation = validateReopenGuard(rule);
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

    /** MANUAL trigger_kind workflows only (e.g. the "reopen valve" layered example) -- see WorkflowRuleService, which never auto-fires these. */
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
        // Manual fires are recorded the same shape a device-triggered
        // execution is, just with no source CabinEvent to point at.
        // Actual action execution reuses WorkflowRuleService's own fire()
        // path in a real implementation; left as a follow-up endpoint stub
        // deliberately narrow in this hardening pass, which is scoped to
        // the leak-shutoff path already proven live, not the reopen path
        // (see ontology's action_main_water_valve_open notes -- reopen
        // ships only after the close path is proven, per the reviewed plan).
        return Map.of("error", "Manual firing is not yet implemented -- reopen ships after the close path is fully proven");
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
