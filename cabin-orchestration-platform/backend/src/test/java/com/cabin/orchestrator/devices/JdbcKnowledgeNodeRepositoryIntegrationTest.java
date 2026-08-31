package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcKnowledgeNodeRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcKnowledgeNodeRepository newRepository() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcKnowledgeNodeRepository(jdbc);
    }

    @Test
    void upsertPersistsAcrossInstances() {
        JdbcKnowledgeNodeRepository first = newRepository();
        first.upsert(new KnowledgeNode("z2m-temp_kitchen_kn1", KnowledgeChunkType.DESCRIPTION,
            "Kitchen temp is a SONOFF SNZB-02WD.", KnowledgeSource.AUTO_GENERATED, Instant.now()));

        JdbcKnowledgeNodeRepository restarted = newRepository();
        List<KnowledgeNode> found = restarted.findByEntityRef("z2m-temp_kitchen_kn1");

        assertEquals(1, found.size());
        assertEquals(KnowledgeChunkType.DESCRIPTION, found.get(0).chunkType());
        assertEquals(KnowledgeSource.AUTO_GENERATED, found.get(0).source());
    }

    @Test
    void upsertOnTheSameEntityAndChunkTypeUpdatesRatherThanDuplicating() {
        JdbcKnowledgeNodeRepository repository = newRepository();
        repository.upsert(new KnowledgeNode("z2m-temp_kitchen_kn2", KnowledgeChunkType.DESCRIPTION,
            "old content", KnowledgeSource.AUTO_GENERATED, Instant.now()));
        repository.upsert(new KnowledgeNode("z2m-temp_kitchen_kn2", KnowledgeChunkType.DESCRIPTION,
            "new content", KnowledgeSource.AUTO_GENERATED, Instant.now()));

        List<KnowledgeNode> found = repository.findByEntityRef("z2m-temp_kitchen_kn2");

        assertEquals(1, found.size());
        assertEquals("new content", found.get(0).content());
    }

    @Test
    void aDeviceCanHaveBothADescriptionAndARelationshipChunk() {
        JdbcKnowledgeNodeRepository repository = newRepository();
        repository.upsert(new KnowledgeNode("z2m-temp_kitchen_kn3", KnowledgeChunkType.DESCRIPTION,
            "description", KnowledgeSource.AUTO_GENERATED, Instant.now()));
        repository.upsert(new KnowledgeNode("z2m-temp_kitchen_kn3", KnowledgeChunkType.RELATIONSHIP,
            "reports: temperature, humidity.", KnowledgeSource.AUTO_GENERATED, Instant.now()));

        List<KnowledgeNode> found = repository.findByEntityRef("z2m-temp_kitchen_kn3");

        assertEquals(2, found.size());
    }
}
