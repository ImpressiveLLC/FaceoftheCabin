package com.cabin.orchestrator.devices.model;

import com.cabin.orchestrator.security.HouseholdRole;

/**
 * WSJF #8 -- the one place CREDENTIAL_POINTER content gets redacted for a
 * non-ADMINISTRATOR caller. Applied everywhere a KnowledgeNode can reach an
 * HTTP response: the Tiny Helpdesk answer path (TinyHelpdeskService, whose
 * prompt/fallback/citations all flow through the same retrieved-node list)
 * and the raw KnowledgeNode listing endpoints (KnowledgeNodeController's
 * GET /api/kb/nodes, open to any caller per WebConfig's GET carve-out) --
 * gating only the Tiny Helpdesk response and leaving the raw listing
 * endpoint unfiltered would make the whole feature a one-line bypass.
 * Every other chunk type (DESCRIPTION, RELATIONSHIP, etc.) is untouched --
 * this only ever rewrites CREDENTIAL_POINTER content, never anything else.
 */
public final class CredentialPointerRedactor {

    private CredentialPointerRedactor() {}

    public static KnowledgeNode redact(KnowledgeNode node, HouseholdRole role) {
        if (node.chunkType() != KnowledgeChunkType.CREDENTIAL_POINTER) return node;
        String content = role == HouseholdRole.ADMINISTRATOR
            ? "Vault entry name: " + node.content() + " — retrieve value from Vaultwarden"
            : "Contact an administrator for this credential.";
        return new KnowledgeNode(node.entityRef(), node.chunkType(), content, node.source(), node.generatedAt());
    }
}
