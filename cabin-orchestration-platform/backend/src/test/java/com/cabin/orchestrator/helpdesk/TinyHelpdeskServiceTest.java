package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TinyHelpdeskServiceTest {

    private FakeKnowledgeNodeRepository knowledgeNodeRepository;
    private FakeOllamaClient ollamaClient;
    private TinyHelpdeskService service;

    @BeforeEach
    void setUp() {
        knowledgeNodeRepository = new FakeKnowledgeNodeRepository();
        ollamaClient = new FakeOllamaClient();
        service = new TinyHelpdeskService(knowledgeNodeRepository, ollamaClient);
    }

    private void addNode(String entityRef, KnowledgeChunkType chunkType, String content) {
        knowledgeNodeRepository.nodes.add(new KnowledgeNode(entityRef, chunkType, content, KnowledgeSource.AUTO_GENERATED, Instant.now()));
    }

    @Test
    void aQuestionMatchingAKnownDeviceRetrievesItAndAsksOllama() {
        addNode("z2m-temp_kitchen", KnowledgeChunkType.DESCRIPTION,
            "temp_kitchen is a SONOFF SNZB-02WD (temperature sensor) located in cabin.");
        ollamaClient.nextResponse = Optional.of("It's a SONOFF temperature sensor in the kitchen.");

        TinyHelpdeskAnswer answer = service.ask("What kind of sensor is in the kitchen?");

        assertTrue(answer.answeredByModel());
        assertEquals("It's a SONOFF temperature sensor in the kitchen.", answer.answer());
        assertEquals(1, answer.sources().size());
        assertEquals(KnowledgeSource.AUTO_GENERATED, answer.sources().get(0).source(),
            "the source field must be visible on the answer, not stripped out");
        assertTrue(ollamaClient.lastPrompt.contains("SNZB-02WD"), "retrieved facts must actually reach the prompt");
    }

    @Test
    void anUnansweredQuestionNeverReachesOllamaAtAll() {
        addNode("z2m-temp_kitchen", KnowledgeChunkType.DESCRIPTION, "temp_kitchen is a SONOFF SNZB-02WD.");

        TinyHelpdeskAnswer answer = service.ask("What's the capital of France?");

        assertFalse(answer.answeredByModel());
        assertTrue(answer.sources().isEmpty());
        assertEquals(0, ollamaClient.callCount, "no relevant facts means no point calling the model at all");
    }

    @Test
    void ollamaUnreachableFallsBackToRawRetrievedFactsNotAnError() {
        addNode("z2m-temp_kitchen", KnowledgeChunkType.RELATIONSHIP, "temp_kitchen reports: humidity, temperature.");
        ollamaClient.nextResponse = Optional.empty();

        TinyHelpdeskAnswer answer = service.ask("What does the kitchen temperature sensor report?");

        assertFalse(answer.answeredByModel());
        assertTrue(answer.answer().contains("humidity"));
        assertFalse(answer.sources().isEmpty(), "the retrieved facts should still surface even without a model answer");
    }

    @Test
    void retrievalIsCaseInsensitiveAndIgnoresStopwords() {
        addNode("z2m-leak_mech_room", KnowledgeChunkType.DESCRIPTION, "leak_mech_room is a Third Reality leak sensor.");
        ollamaClient.nextResponse = Optional.of("answer");

        TinyHelpdeskAnswer answer = service.ask("Where IS the LEAK sensor?");

        assertEquals(1, answer.sources().size());
    }

    @Test
    void anAdministratorAskingAboutACredentialGetsTheRealVaultEntryName() {
        addNode("Blink Cloud Account", KnowledgeChunkType.CREDENTIAL_POINTER, "vault_blink_username, vault_blink_password");
        ollamaClient.nextResponse = Optional.empty(); // fallback path -- exercises raw content directly, no model paraphrase to hide behind

        TinyHelpdeskAnswer answer = service.ask("How do I access the Blink account?", HouseholdRole.ADMINISTRATOR);

        assertTrue(answer.answer().contains("vault_blink_username"), "an administrator must see the real vault entry name");
        assertTrue(ollamaClient.lastPrompt.contains("vault_blink_username"), "and it must reach the model prompt too, not just the fallback");
    }

    @Test
    void aNonAdministratorAskingAboutACredentialNeverSeesTheVaultEntryName() {
        addNode("Blink Cloud Account", KnowledgeChunkType.CREDENTIAL_POINTER, "vault_blink_username, vault_blink_password");
        ollamaClient.nextResponse = Optional.empty();

        TinyHelpdeskAnswer answer = service.ask("How do I access the Blink account?", HouseholdRole.ADULT_HOUSEHOLD_MEMBER);

        assertFalse(answer.answer().contains("vault_"), "no vault entry name may leak into a non-administrator's answer");
        assertTrue(answer.answer().contains("Contact an administrator"));
        assertFalse(ollamaClient.lastPrompt.contains("vault_"), "nor into the model prompt -- the model must never see it either");
    }

    @Test
    void aQuestionWithNoRoleAtAllIsTreatedAsNonAdministrator() {
        addNode("Resend", KnowledgeChunkType.CREDENTIAL_POINTER, "vault_resend_api_key");
        ollamaClient.nextResponse = Optional.empty();

        TinyHelpdeskAnswer answer = service.ask("How do I access Resend?", null);

        assertFalse(answer.answer().contains("vault_"));
    }

    @Test
    void theOneArgAskOverloadStillWorksExactlyAsBeforeForNonCredentialContent() {
        addNode("z2m-temp_kitchen", KnowledgeChunkType.DESCRIPTION, "temp_kitchen is a SONOFF sensor.");
        ollamaClient.nextResponse = Optional.of("It's a SONOFF sensor.");

        TinyHelpdeskAnswer answer = service.ask("What sensor is in the kitchen?");

        assertEquals("It's a SONOFF sensor.", answer.answer());
    }

    private static final class FakeKnowledgeNodeRepository implements KnowledgeNodeRepository {
        final List<KnowledgeNode> nodes = new ArrayList<>();
        @Override public void upsert(KnowledgeNode node) { nodes.add(node); }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) {
            return nodes.stream().filter(n -> n.entityRef().equals(entityRef)).toList();
        }
        @Override public List<KnowledgeNode> loadAll() { return List.copyOf(nodes); }
    }

    private static final class FakeOllamaClient implements OllamaClient {
        Optional<String> nextResponse = Optional.empty();
        String lastPrompt;
        int callCount = 0;
        @Override public Optional<String> generate(String prompt) {
            callCount++;
            lastPrompt = prompt;
            return nextResponse;
        }
    }
}
