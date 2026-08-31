package com.cabin.orchestrator.devices.model;

import java.time.Instant;

/**
 * L6 (docs/ontology/DECISIONS.md) -- a Tiny Helpdesk-consumable, ≤500-token
 * content chunk about one entity. entityRef + chunkType is the natural key:
 * one description and one relationship chunk per device today, not an
 * arbitrary list, so regenerating just updates the same row rather than
 * accumulating stale duplicates.
 *
 * No entity_type field yet (the wider ontology's KnowledgeNode concept has
 * one -- device | area | rule | service | procedure): every node KB
 * Generator v1 writes is device-sourced, so entityRef being a device_id is
 * unambiguous today. Add entity_type when a second kind of node (area/rule)
 * actually gets built, not speculatively ahead of that.
 */
public record KnowledgeNode(
    String entityRef,
    KnowledgeChunkType chunkType,
    String content,
    KnowledgeSource source,
    Instant generatedAt
) {}
