package com.cabin.orchestrator.api;

import com.cabin.orchestrator.workflow.JdbcWorkflowExecutionStore;
import com.cabin.orchestrator.workflow.JdbcWorkflowRuleStore;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Human-configured workflow rule CRUD + execution history/view-tracking.
 *
 * v1 (2026-08-14): vocabulary lookup endpoints (GET .../vocabulary/*) are
 * NOT included here -- that's Codex's parallel slice (OntologyLookupService
 * reverse-lookup), landing separately. Workflow creation today is a plain
 * POST with a fully-formed WorkflowRule body (used to seed the flagship
 * leak-shutoff workflow); the guided picker UI is real follow-up work, not
 * built here.
 */
@RestController
@RequestMapping("/api/rules")
@CrossOrigin
public class RulesController {

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
    public Map<String, Object> createWorkflow(@RequestBody WorkflowRule rule) {
        ruleStore.save(rule);
        return Map.of("workflowId", rule.workflowId());
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
    public Map<String, Object> clearExecution(@PathVariable String id,
                                               @RequestBody(required = false) Map<String, Object> body) {
        Optional<WorkflowExecution> existing = executionStore.findById(id);
        if (existing.isEmpty()) return Map.of("error", "not found");
        WorkflowExecution e = existing.get();
        Object actorId = body == null ? null : body.get("actorId");
        String clearedBy = "HUMAN:" + (actorId != null ? actorId : "unknown");
        executionStore.save(new WorkflowExecution(
            e.executionId(), e.workflowId(), e.triggeredByEventId(), e.firedAt(),
            Instant.now(), clearedBy, e.actionResults(), e.lastViewedAt()));
        return Map.of("executionId", id, "cleared", true);
    }
}
