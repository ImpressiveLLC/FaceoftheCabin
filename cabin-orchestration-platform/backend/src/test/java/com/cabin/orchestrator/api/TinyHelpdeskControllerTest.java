package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import com.cabin.orchestrator.helpdesk.OllamaClient;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskAnswer;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring only -- TinyHelpdeskService's own retrieval/prompt logic is covered in TinyHelpdeskServiceTest. */
class TinyHelpdeskControllerTest {

    @Test
    void askDelegatesTheQuestionFieldToTheService() {
        List<KnowledgeNode> nodes = new ArrayList<>();
        nodes.add(new KnowledgeNode("z2m-temp_kitchen", KnowledgeChunkType.DESCRIPTION,
            "temp_kitchen is a SONOFF SNZB-02WD.", KnowledgeSource.AUTO_GENERATED, Instant.now()));
        KnowledgeNodeRepository repository = new KnowledgeNodeRepository() {
            @Override public void upsert(KnowledgeNode node) { }
            @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
            @Override public List<KnowledgeNode> loadAll() { return nodes; }
        };
        OllamaClient ollamaClient = prompt -> Optional.of("It's a SONOFF sensor.");
        TinyHelpdeskController controller = new TinyHelpdeskController(new TinyHelpdeskService(repository, ollamaClient));

        TinyHelpdeskAnswer answer = controller.ask(Map.of("question", "What sensor is in the kitchen?"));

        assertEquals("It's a SONOFF sensor.", answer.answer());
        assertTrue(answer.answeredByModel());
    }

    @Test
    void aMissingQuestionFieldIsTreatedAsAnEmptyQuestionNotAnError() {
        KnowledgeNodeRepository repository = new KnowledgeNodeRepository() {
            @Override public void upsert(KnowledgeNode node) { }
            @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
            @Override public List<KnowledgeNode> loadAll() { return List.of(); }
        };
        OllamaClient ollamaClient = prompt -> Optional.empty();
        TinyHelpdeskController controller = new TinyHelpdeskController(new TinyHelpdeskService(repository, ollamaClient));

        TinyHelpdeskAnswer answer = controller.ask(Map.of());

        assertFalse(answer.answeredByModel());
    }
}
