package com.cabin.orchestrator.ontology;

/** Minimal, non-technical-safe view of one docs/ontology.yaml entity. */
public record OntologyEntitySummary(
    String id,
    String uiDisplayName,
    String entityType,
    boolean found
) {}
