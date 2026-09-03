package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * WSJF #8 -- seeds the two real CREDENTIAL_POINTER KnowledgeNodes Tiny
 * Helpdesk v2 demonstrates the feature with. Always MANUALLY_CURATED (D5's
 * hard rule -- KbGeneratorService structurally never writes this chunk
 * type, see its own guard). content is always a vault ENTRY NAME, never a
 * real secret value -- see ansible/group_vars/cabin/vault.yml for where
 * the actual values live.
 *
 * Seeded only once per entityRef: an admin who later hand-edits one of
 * these via POST /api/kb/curate must never have that edit silently
 * reverted on the next restart -- the same "don't clobber a stronger
 * existing claim" rule KbGeneratorService already applies to
 * DESCRIPTION/RELATIONSHIP, applied here to a MANUALLY_CURATED-only
 * chunk type.
 */
@Component
public class CredentialPointerSeeder {

    private static final List<KnowledgeNode> SEEDS = List.of(
        new KnowledgeNode("Blink Cloud Account", KnowledgeChunkType.CREDENTIAL_POINTER,
            "vault_blink_username, vault_blink_password", KnowledgeSource.MANUALLY_CURATED, Instant.now()),
        new KnowledgeNode("Resend", KnowledgeChunkType.CREDENTIAL_POINTER,
            "vault_resend_api_key", KnowledgeSource.MANUALLY_CURATED, Instant.now())
    );

    private final KnowledgeNodeRepository repository;

    public CredentialPointerSeeder(KnowledgeNodeRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void seedIfMissing() {
        for (KnowledgeNode seed : SEEDS) {
            boolean alreadyExists = repository.findByEntityRef(seed.entityRef()).stream()
                .anyMatch(existing -> existing.chunkType() == seed.chunkType());
            if (!alreadyExists) {
                repository.upsert(seed);
            }
        }
    }
}
