package com.cabin.orchestrator.devices.model;

/**
 * The chunk_type vocabulary the ontology's KnowledgeNode entity declares.
 * KB Generator v1 (issue #32) only ever writes DESCRIPTION and
 * RELATIONSHIP -- factual device metadata, never a procedure. TROUBLESHOOTING,
 * SETUP, and CREDENTIAL_POINTER exist here for the vocabulary's own
 * completeness and for a future manually_curated authoring path, but D5's
 * safety-critical exclusion for auto-generated content is enforced by KB
 * Generator simply never producing these chunk types at all, not by a
 * per-device runtime check -- see KbGeneratorService's own comment.
 */
public enum KnowledgeChunkType {
    DESCRIPTION,
    SETUP,
    TROUBLESHOOTING,
    CREDENTIAL_POINTER,
    RELATIONSHIP;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static KnowledgeChunkType fromDbValue(String value) {
        return KnowledgeChunkType.valueOf(value.toUpperCase());
    }
}
