package com.cabin.orchestrator.devices.model;

/**
 * One real, queryable row for each {@link DeviceLifecycleState} value -- the
 * "one-stop source of truth" the device-manager investigation asked for,
 * mirroring JdbcWorkflowVocabularyStore's existing pattern for triggers/
 * actions. inScope/previouslyExposed/allowsActiveUse are populated straight
 * from the enum's own predicate methods (see JdbcDeviceLifecycleVocabularyStore),
 * not hand-duplicated, so this table can never drift from what the engine
 * actually enforces.
 */
public record LifecycleStateVocabularyEntry(
    String id,
    String label,
    boolean inScope,
    boolean previouslyExposed,
    boolean allowsActiveUse
) {}
