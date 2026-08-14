package com.cabin.orchestrator.workflow.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One firing of a WorkflowRule. clearedAt/clearedBy track the "resets only
 * after a human interacts or the trigger condition clears" requirement --
 * both paths are always available, not a mode switch (see WorkflowRuleService
 * for AUTO_ON_CLEAR vs MANUAL_ONLY). lastViewedAt powers the "recent event"
 * tile badge: unviewed = lastViewedAt is null or predates firedAt.
 */
public record WorkflowExecution(
    String executionId,
    String workflowId,
    String triggeredByEventId,
    Instant firedAt,
    Instant clearedAt,      // nullable
    String clearedBy,       // nullable -- "AUTO" | "HUMAN:{actorId}"
    List<Map<String, Object>> actionResults,
    Instant lastViewedAt    // nullable
) {}
