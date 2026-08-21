package com.cabin.orchestrator.workflow.model;

import java.util.Map;

/**
 * One ordered step within a WorkflowRule's action list.
 *
 * cooldownSeconds (nullable, added 2026-08-21): null preserves the original
 * behavior -- this action always runs whenever its rule fires. A set value
 * means "skip me if I already ran successfully within the last N seconds,"
 * checked independently per action -- see WorkflowRuleService.executeAction().
 * This is what lets one action in a rule re-fire on every occurrence (e.g.
 * notify_critical, no cooldown) while a sibling actuator action in the same
 * rule is rate-limited or effectively one-shot (a cooldown set), without
 * touching the rule-level findActive() edge-detection guard, which exists
 * for a different reason (suppressing repeat sampling of one unchanged
 * ongoing state, see that method's own comment).
 */
public record WorkflowAction(
    String actionId,
    String workflowId,
    int stepOrder,
    String actionDefinitionId,
    String targetDeviceId,    // null for non-command actions (log/notify)
    Map<String, Object> actionConfig,
    Integer cooldownSeconds   // nullable -- see class doc
) {}
