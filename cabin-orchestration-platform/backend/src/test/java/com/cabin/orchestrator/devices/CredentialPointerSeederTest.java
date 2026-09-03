package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CredentialPointerSeederTest {

    private FakeKnowledgeNodeRepository repository;
    private CredentialPointerSeeder seeder;

    @BeforeEach
    void setUp() {
        repository = new FakeKnowledgeNodeRepository();
        seeder = new CredentialPointerSeeder(repository);
    }

    @Test
    void seedsBothRealEntriesAsManuallyCuratedVaultEntryNamesOnly() {
        seeder.seedIfMissing();

        List<KnowledgeNode> all = repository.loadAll();
        assertEquals(2, all.size());
        assertTrue(all.stream().allMatch(n -> n.chunkType() == KnowledgeChunkType.CREDENTIAL_POINTER));
        assertTrue(all.stream().allMatch(n -> n.source() == KnowledgeSource.MANUALLY_CURATED),
            "D5's hard rule -- credential pointers are never auto_generated");

        KnowledgeNode blink = repository.findByEntityRef("Blink Cloud Account").get(0);
        assertEquals("vault_blink_username, vault_blink_password", blink.content(),
            "content must be vault entry names only, never a real secret value");
        KnowledgeNode resend = repository.findByEntityRef("Resend").get(0);
        assertEquals("vault_resend_api_key", resend.content());
    }

    @Test
    void neverOverwritesAnAdminsOwnHandEditedEntry() {
        repository.upsert(new KnowledgeNode("Resend", KnowledgeChunkType.CREDENTIAL_POINTER,
            "vault_resend_api_key_rotated_2026", KnowledgeSource.MANUALLY_CURATED, Instant.now()));

        seeder.seedIfMissing();

        KnowledgeNode resend = repository.findByEntityRef("Resend").get(0);
        assertEquals("vault_resend_api_key_rotated_2026", resend.content(),
            "a later restart must never silently revert an admin's own /api/kb/curate edit");
    }

    @Test
    void isIdempotentAcrossRepeatedCalls() {
        seeder.seedIfMissing();
        seeder.seedIfMissing();

        assertEquals(2, repository.loadAll().size());
    }

    private static final class FakeKnowledgeNodeRepository implements KnowledgeNodeRepository {
        private final List<KnowledgeNode> nodes = new ArrayList<>();
        @Override public void upsert(KnowledgeNode node) {
            nodes.removeIf(n -> n.entityRef().equals(node.entityRef()) && n.chunkType() == node.chunkType());
            nodes.add(node);
        }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) {
            return nodes.stream().filter(n -> n.entityRef().equals(entityRef)).toList();
        }
        @Override public List<KnowledgeNode> loadAll() { return List.copyOf(nodes); }
    }
}
