package com.cabin.orchestrator.workflow.model;

import java.util.List;

/**
 * Rule-level rollup of its actions' WorkflowActionHealthEntry list --
 * attached as an additive key on GET /api/rules/workflows (see
 * RulesController.listWorkflows()), closing the real gap that a workflow
 * could silently stop being able to fire (its target device removed,
 * reassigned, or gone missing) with no signal anywhere in the UI until it
 * actually failed to run.
 *
 * BROKEN: at least one action isn't healthy() -- it would be rejected if
 * this workflow were resubmitted through create/activate right now, so it
 * would just as surely fail (or silently no-op) the moment it fires.
 * DEGRADED: every action is structurally healthy, but at least one target
 * device isn't currently reachable (CheckinStatus.MISSED) -- the workflow
 * would very likely still work, just a softer warning than BROKEN.
 * HEALTHY: everything checks out.
 */
public record WorkflowHealth(String status, List<WorkflowActionHealthEntry> actions) {

    public static WorkflowHealth of(List<WorkflowActionHealthEntry> actions) {
        boolean broken = actions.stream().anyMatch(a -> !a.healthy());
        boolean degraded = !broken && actions.stream().anyMatch(a -> !a.online());
        String status = broken ? "BROKEN" : degraded ? "DEGRADED" : "HEALTHY";
        return new WorkflowHealth(status, actions);
    }
}
