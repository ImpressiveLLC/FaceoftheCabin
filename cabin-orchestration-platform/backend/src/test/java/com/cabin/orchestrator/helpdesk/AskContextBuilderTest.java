package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AskContextBuilderTest {

    private FakeKnowledgeNodeRepository repository;
    private AskContextBuilder builder;

    @BeforeEach
    void setUp() {
        repository = new FakeKnowledgeNodeRepository();
        addNode(repository, "system.presence", KnowledgeChunkType.DESCRIPTION, "Presence description.");
        addNode(repository, "system.presence", KnowledgeChunkType.TROUBLESHOOTING, "Presence troubleshooting.");
        addNode(repository, "system.sensors", KnowledgeChunkType.DESCRIPTION, "Sensors description.");
        addNode(repository, "system.cameras", KnowledgeChunkType.DESCRIPTION, "Cameras description.");
        addNode(repository, "system.cameras", KnowledgeChunkType.TROUBLESHOOTING, "Cameras troubleshooting.");
        builder = new AskContextBuilder(repository, new DefaultResourceLoader(),
            "classpath:rag/context-fixtures-r1.json");
    }

    @Test
    void liveSensorQuestion_returnsSensorAndPresenceNodes() {
        List<KnowledgeNode> result = builder.buildContext("What is the temperature right now?");

        assertThat(result.stream().map(KnowledgeNode::entityRef)).contains("system.sensors", "system.presence");
    }

    @Test
    void cameraQuestion_returnsCameraNodes() {
        List<KnowledgeNode> result = builder.buildContext(
            "My camera card is empty -- does that mean nobody was home?");

        assertThat(result.stream().map(KnowledgeNode::entityRef)).contains("system.cameras");
    }

    @Test
    void unrecognizedQuestion_returnsEmptyList() {
        List<KnowledgeNode> result = builder.buildContext("What is the weather in Paris?");

        assertThat(result).isEmpty();
    }

    @Test
    void missingNode_isSkippedNotFailed() {
        FakeKnowledgeNodeRepository sparseRepository = new FakeKnowledgeNodeRepository();
        addNode(sparseRepository, "system.cameras", KnowledgeChunkType.DESCRIPTION, "Cameras description.");
        AskContextBuilder sparseBuilder = new AskContextBuilder(sparseRepository, new DefaultResourceLoader(),
            "classpath:rag/context-fixtures-r1.json");

        List<KnowledgeNode> result = sparseBuilder.buildContext("camera card is empty");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entityRef()).isEqualTo("system.cameras");
    }

    private static void addNode(FakeKnowledgeNodeRepository repository, String entityRef,
            KnowledgeChunkType chunkType, String content) {
        repository.nodes.add(new KnowledgeNode(entityRef, chunkType, content, KnowledgeSource.AUTO_GENERATED, Instant.now()));
    }

    private static final class FakeKnowledgeNodeRepository implements KnowledgeNodeRepository {
        final List<KnowledgeNode> nodes = new ArrayList<>();
        @Override public void upsert(KnowledgeNode node) { nodes.add(node); }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) {
            return nodes.stream().filter(n -> n.entityRef().equals(entityRef)).toList();
        }
        @Override public List<KnowledgeNode> loadAll() { return List.copyOf(nodes); }
    }
}
