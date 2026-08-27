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
    boolean supported,
    // nullable, added 2026-08-27 alongside DeviceType.telemetryFields() --
    // for a trigger keyed off a payload field that spans more than one
    // DeviceType (e.g. trigger_mold_risk_detected's "humidity", reported by
    // both TEMPERATURE_SENSOR and HUMIDITY_SENSOR), appliesToDeviceType
    // alone can only express one type or "any type at all" (null). Setting
    // this instead tells the frontend's device-scoping picker to filter by
    // DeviceStatus.attributes.reportsFields containing this value, not by
    // device type -- see App.jsx's triggerScopedDevices.
    String appliesToField
) {}
