package com.cabin.orchestrator.workflow.model;

/**
 * One action a workflow can run, and whether WorkflowRuleService.executeAction()
 * actually implements it yet. See TriggerVocabularyEntry's own doc for the
 * supported/candidate split.
 *
 * targetDeviceId (nullable) is set only for an INSTANCE-specific action --
 * today, action_main_water_valve_off/_open command "the" one main shutoff,
 * not "a" valve of a class (see that entity's own docs/ontology.yaml notes).
 * When set, the creation form should lock the device field instead of
 * offering a free picker, closing a real mistargeting risk: nothing today
 * stopped a person from pointing this action at an unrelated device.
 *
 * privileged mirrors RulesController.validateReopenGuard()'s hardcoded
 * PRIVILEGED_REOPEN_ACTION check -- this field is informational only (lets
 * the creation form hide/explain the restriction up front) and changes
 * nothing about server-side enforcement, which stays in that guard, not
 * driven by this table. This table has no write endpoint, so it can never
 * become a way to toggle that guard off.
 */
public record ActionVocabularyEntry(
    String id,
    String label,
    String actionKind,
    String requiresCapability,  // nullable
    boolean needsTarget,
    String targetDeviceId,      // nullable
    boolean privileged,
    boolean supported
) {}
