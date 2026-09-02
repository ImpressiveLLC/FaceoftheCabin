package com.cabin.orchestrator.devices.model;

/**
 * One real, queryable row for each {@link DeviceLifecycleAction} value.
 * targetState is populated from the enum's own targetState() -- see
 * LifecycleStateVocabularyEntry's own doc for why this is derived, not
 * hand-authored.
 */
public record LifecycleActionVocabularyEntry(
    String id,
    String label,
    String targetState
) {}
