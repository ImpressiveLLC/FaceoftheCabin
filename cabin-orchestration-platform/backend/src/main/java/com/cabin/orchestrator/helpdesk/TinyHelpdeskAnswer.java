package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.model.KnowledgeNode;

import java.util.List;

/**
 * Sprint 2's roadmap explicitly requires each KnowledgeNode's `source`
 * (auto_generated | manually_curated) be visible in chat responses, not
 * just used internally -- `sources` carries the full nodes (source field
 * included) for exactly that reason, not just their text.
 */
public record TinyHelpdeskAnswer(
    String question,
    String answer,
    List<KnowledgeNode> sources,
    boolean answeredByModel
) {}
