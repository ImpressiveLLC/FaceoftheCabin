package com.cabin.orchestrator.workflow.model;

/**
 * One trigger a workflow can be built against, and whether
 * WorkflowRuleService actually interprets it yet. {@code supported=true}
 * rows come from the seeded trigger_vocabulary table (kept in sync by hand
 * with WorkflowRuleService.resolveTriggerDefinitionId()'s real switch);
 * {@code supported=false} rows are merged in live from docs/ontology.yaml's
 * own candidate trigger_* entities (OntologyLookupService) -- see
 * RulesController's vocabulary endpoints.
 */
public record TriggerVocabularyEntry(
    String id,
    String label,
    String appliesToDeviceType,  // nullable -- informs the device-scoping picker, not enforced by the engine
    String appliesToCapability,  // nullable
    boolean supported
) {}
