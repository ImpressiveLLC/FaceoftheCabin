package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.KnowledgeNode;

import java.util.List;

/** Persists L6 KnowledgeNode chunks (issue #32) -- see KnowledgeNode's own doc comment. */
public interface KnowledgeNodeRepository {
    void upsert(KnowledgeNode node);
    List<KnowledgeNode> findByEntityRef(String entityRef);
    List<KnowledgeNode> loadAll();
}
