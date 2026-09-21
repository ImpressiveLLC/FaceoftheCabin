package com.cabin.orchestrator.devices.model;

/**
 * D5 (docs/ontology/DECISIONS.md): every KnowledgeNode a generator writes
 * must be tagged AUTO_GENERATED so the Tiny Helpdesk can visibly
 * distinguish it from a person-authored MANUALLY_CURATED node. Nothing in
 * this codebase writes MANUALLY_CURATED yet -- that's a future authoring
 * UI, not KB Generator v1's job.
 *
 * REVIEWED_DOCUMENT is never written to knowledge_node: it tags an in-memory
 * section of an allowlisted repository Markdown file that AskContextBuilder
 * supplies as context (see ReviewedDocumentSource). It is deliberately not
 * MANUALLY_CURATED -- a repository doc reviewed through a PR is not a
 * person-curated KnowledgeNode, and safety-critical content stays curated
 * KnowledgeNodes only (D5).
 */
public enum KnowledgeSource {
    AUTO_GENERATED,
    MANUALLY_CURATED,
    REVIEWED_DOCUMENT;

    public String dbValue() {
        return name().toLowerCase();
    }

    public static KnowledgeSource fromDbValue(String value) {
        return KnowledgeSource.valueOf(value.toUpperCase());
    }
}
