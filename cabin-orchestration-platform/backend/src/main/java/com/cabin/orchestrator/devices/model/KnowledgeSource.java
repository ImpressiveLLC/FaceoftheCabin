package com.cabin.orchestrator.devices.model;

/**
 * D5 (docs/ontology/DECISIONS.md): every KnowledgeNode a generator writes
 * must be tagged AUTO_GENERATED so the Tiny Helpdesk can visibly
 * distinguish it from a person-authored MANUALLY_CURATED node. Nothing in
 * this codebase writes MANUALLY_CURATED yet -- that's a future authoring
 * UI, not KB Generator v1's job.
 */
public enum KnowledgeSource {
    AUTO_GENERATED,
    MANUALLY_CURATED;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static KnowledgeSource fromDbValue(String value) {
        return KnowledgeSource.valueOf(value.toUpperCase());
    }
}
