package com.cabin.orchestrator.workflow.model;

import java.time.Instant;
import java.util.List;

/**
 * A human-configured trigger -> ordered-actions rule. trigger_kind=MANUAL
 * rules have null triggerDefinitionId/triggerDeviceId and fire only via an
 * explicit POST .../fire call (e.g. the "reopen valve" layered-workflow
 * example) -- never matched against incoming CabinEvents.
 */
public record WorkflowRule(
    String workflowId,
    String name,
    String location,
    String triggerKind,          // DEVICE_EVENT | MANUAL
    String triggerDefinitionId,  // null when triggerKind=MANUAL
    String triggerDeviceId,      // null = matches any device of the trigger's type
    boolean enabled,
    String resetMode,            // AUTO_ON_CLEAR | MANUAL_ONLY
    String parentWorkflowId,     // nullable -- UI grouping only, never an execution gate
    Instant createdAt,
    String createdBy,
    List<WorkflowAction> actions
) {}
