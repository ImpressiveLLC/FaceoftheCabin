package com.cabin.orchestrator.workflow.model;

import java.util.Map;

/** One ordered step within a WorkflowRule's action list. */
public record WorkflowAction(
    String actionId,
    String workflowId,
    int stepOrder,
    String actionDefinitionId,
    String targetDeviceId,    // null for non-command actions (log/notify)
    Map<String, Object> actionConfig
) {}
