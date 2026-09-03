package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import com.cabin.orchestrator.helpdesk.OllamaClient;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskAnswer;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring only -- TinyHelpdeskService's own retrieval/prompt logic is covered in TinyHelpdeskServiceTest. */
class TinyHelpdeskControllerTest {

    private static MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/helpdesk/ask");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

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

        TinyHelpdeskAnswer answer = controller.ask(Map.of("question", "What sensor is in the kitchen?"), requestWithRole(null));

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

        TinyHelpdeskAnswer answer = controller.ask(Map.of(), requestWithRole(null));

        assertFalse(answer.answeredByModel());
    }

    @Test
    void anAdministratorRequestSeesTheRealVaultEntryNameEndToEnd() {
        List<KnowledgeNode> nodes = new ArrayList<>();
        nodes.add(new KnowledgeNode("Resend", KnowledgeChunkType.CREDENTIAL_POINTER,
            "vault_resend_api_key", KnowledgeSource.MANUALLY_CURATED, Instant.now()));
        KnowledgeNodeRepository repository = new KnowledgeNodeRepository() {
            @Override public void upsert(KnowledgeNode node) { }
            @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
            @Override public List<KnowledgeNode> loadAll() { return nodes; }
        };
        OllamaClient ollamaClient = prompt -> Optional.empty();
        TinyHelpdeskController controller = new TinyHelpdeskController(new TinyHelpdeskService(repository, ollamaClient));

        TinyHelpdeskAnswer answer = controller.ask(Map.of("question", "How do I access Resend?"), requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertTrue(answer.answer().contains("vault_resend_api_key"));
    }

    @Test
    void aNonAdministratorRequestNeverSeesTheVaultEntryNameEndToEnd() {
        List<KnowledgeNode> nodes = new ArrayList<>();
        nodes.add(new KnowledgeNode("Resend", KnowledgeChunkType.CREDENTIAL_POINTER,
            "vault_resend_api_key", KnowledgeSource.MANUALLY_CURATED, Instant.now()));
        KnowledgeNodeRepository repository = new KnowledgeNodeRepository() {
            @Override public void upsert(KnowledgeNode node) { }
            @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
            @Override public List<KnowledgeNode> loadAll() { return nodes; }
        };
        OllamaClient ollamaClient = prompt -> Optional.empty();
        TinyHelpdeskController controller = new TinyHelpdeskController(new TinyHelpdeskService(repository, ollamaClient));

        TinyHelpdeskAnswer answer = controller.ask(Map.of("question", "How do I access Resend?"), requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertFalse(answer.answer().contains("vault_"));
    }
}
