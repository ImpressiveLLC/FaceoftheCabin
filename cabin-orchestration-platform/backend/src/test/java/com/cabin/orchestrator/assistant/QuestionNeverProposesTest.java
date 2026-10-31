package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.assistant.ProposalModel.Response;
import com.cabin.orchestrator.assistant.ProposalModel.Status;
import com.cabin.orchestrator.devices.DeviceLifecycleRecord;
import com.cabin.orchestrator.devices.DeviceLifecycleStore;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceRepository;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskAnswer;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskService;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Refocus order item 2 (W-117 boundary): a question-shaped request never produces a proposal, a step or a resolved action
 * target, whatever the language model says. The model is the only thing deciding between answering and acting and it is
 * fallible (the first boundary run sent "Can I turn the porch light on from here?" to set_device_state), so the route checks
 * the shape of the request itself.
 */
class QuestionNeverProposesTest {

    static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

    /** Question-shaped phrasings from the boundary candidates, covering each area, plus the one the model got wrong. */
    static final List<String> QUESTIONS = List.of(
        "Can I turn the porch light on from here?",
        "How do I turn off the heater from the app?",
        "How do I silence an alert for an hour?",
        "Can I silence the siren?",
        "Why is the main water valve closed?",
        "What does Fire do on a workflow?",
        "How do I create a workflow?",
        "Can I undo what a workflow just did?",
        "How do I remove a device?",
        "What does Saved for later mean?",
        "Who can disarm the alarm?",
        "How do I make someone a primary person at the cabin?",
        "How do I free up space on the disk?",
        "Where is the API token kept?",
        "Should I restart the backend?",
        "Does a contractor count as being home?",
        "Is a workflow the same as a Home Assistant automation?",
        "Why didn't my workflow run?",
        "How do I turn the bathroom fan on?");

    /** What a wrong model could say for any of them: every intent that would make a proposal. */
    static final List<String> ACTION_OUTPUTS = List.of(
        "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"light\",\"state\":\"on\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
        "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"heater\",\"location\":\"mech room\",\"state\":\"off\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
        "{\"intent\":\"set_device_attribute\",\"slots\":{\"device_name\":\"thermostat\",\"site\":\"cabin\",\"attribute_type\":\"temperature\",\"attribute_value\":\"68\",\"unit\":\"F\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");

    final AtomicReference<String> modelOutput = new AtomicReference<>();
    final List<ProposalAuditLog.Entry> audit = new ArrayList<>();
    final TinyHelpdeskService helpdesk = mock(TinyHelpdeskService.class);
    final IntentProposalService service = build();

    private IntentProposalService build() {
        Map<String, DeviceMetadata> metadata = new HashMap<>();
        DeviceRepository repository = new DeviceRepository() {
            @Override public void upsert(String deviceId, DeviceMetadata m) { metadata.put(deviceId, m); }
            @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.ofNullable(metadata.get(deviceId)); }
            @Override public Map<String, DeviceMetadata> loadAll() { return metadata; }
        };
        DeviceLifecycleStore store = new DeviceLifecycleStore() {
            @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.of(); }
            @Override public void save(DeviceLifecycleRecord record) { }
            @Override public void delete(String deviceId) { }
        };
        DeviceRegistry registry = new DeviceRegistry(List.of(), store, repository);
        add(registry, "heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.COMMAND));
        add(registry, "light_porch", "Porch Light", DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.COMMAND));
        add(registry, "light", "Light", DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.COMMAND));
        add(registry, "thermostat_cabin", "Thermostat", DeviceType.THERMOSTAT, Set.of(DeviceCapability.CLIMATE, DeviceCapability.COMMAND));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        IntentChatClient chat = (model, system, user, format) -> {
            String out = modelOutput.get();
            return out == null ? Optional.empty() : Optional.of(new IntentChatClient.ChatResult(model, out, 5));
        };
        when(helpdesk.ask(any(), any())).thenReturn(new TinyHelpdeskAnswer("q", "a", List.of(), false));
        return new IntentProposalService(chat, new IntentSlotContract(), new AssistantInventoryService(registry, clock),
            new EntityResolver(clock, Duration.ofSeconds(120), Duration.ofHours(6)), helpdesk, audit::add, clock,
            true, "cabin-intent-slot-prompt-r1", Duration.ofSeconds(300));
    }

    private static void add(DeviceRegistry registry, String id, String name, DeviceType type, Set<DeviceCapability> caps) {
        registry.registerDescriptor(new DeviceDescriptor(id, name, type, caps, "ha_rest", "switch." + id, true, "cabin"));
        registry.update(new DeviceStatus(id, type, name, "ONLINE", NOW.minusSeconds(20), new HashMap<>(), "cabin"));
    }

    private Response ask(String text, String modelSays) {
        modelOutput.set(modelSays);
        return service.propose(text, HouseholdRole.ADMINISTRATOR, "admin@example.test");
    }

    @Test
    void aQuestionNeverYieldsAProposalAStepOrAResolvedTargetWhateverActionTheModelPicks() {
        for (String question : QUESTIONS) {
            for (String output : ACTION_OUTPUTS) {
                Response r = ask(question, output);
                String why = question + " <- " + output;
                assertNull(r.proposal(), "no proposal: " + why);
                assertTrue(r.steps().isEmpty(), "no resolved step or target: " + why);
                assertTrue(r.refusals().isEmpty(), "no resolver run at all: " + why);
                assertEquals(Status.CLARIFY, r.status(), why);
                assertEquals("QUESTION_SHAPED", r.code(), why);
                assertNotNull(r.message());
            }
        }
    }

    @Test
    void theWithheldProposalIsRecordedInTheAuditLog() {
        Response r = ask("Can I turn the porch light on from here?", ACTION_OUTPUTS.get(0));

        ProposalAuditLog.Entry entry = audit.get(audit.size() - 1);
        assertEquals("CLARIFY", entry.status());
        assertEquals("QUESTION_SHAPED", entry.code());
        assertNull(entry.proposalId());
        assertEquals("QUESTION_SHAPED", entry.decisions().get(0).outcome());
        assertNull(r.proposal());
    }

    @Test
    void whenTheModelAnswersTheQuestionItReachesTheAskPathAndStillNeverMakesAProposal() {
        for (String question : QUESTIONS) {
            Response r = ask(question, "{\"intent\":\"answer_question\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}");
            assertEquals(Status.ANSWER, r.status(), question);
            assertNull(r.proposal(), question);
            assertTrue(r.steps().isEmpty(), question);
            assertNotNull(r.answer(), question);
        }
    }

    @Test
    void aPlainCommandAndAPoliteRequestToTheAssistantStillMakeAProposal() {
        for (String command : List.of("Turn on the porch light", "turn the heater off", "Please turn on the porch light",
                "Can you turn on the porch light?", "Could you switch the porch light on", "Would you turn the porch light on",
                "Turn on the porch light?")) {
            Response r = ask(command, "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"porch light\",\"state\":\"on\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");
            assertEquals(Status.PROPOSAL, r.status(), command);
            assertNotNull(r.proposal(), command);
        }
    }

    @Test
    void readingADevicesStateIsNotAnActionAndKeepsItsResolvedTarget() {
        Response r = ask("Is the porch light on?",
            "{\"intent\":\"get_device_state\",\"slots\":{\"device_name\":\"porch light\"},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}");

        assertEquals(Status.TARGET, r.status());
        assertNull(r.proposal(), "a read is never a proposal");
        assertFalse(r.steps().isEmpty(), "a state query resolves its target by design");
    }

    @Test
    void theShapeCheckIsNarrow() {
        assertTrue(QuestionShape.isQuestion("How do I turn off the heater"));
        assertTrue(QuestionShape.isQuestion("  why is the valve closed?"));
        assertTrue(QuestionShape.isQuestion("Can I silence the siren"));
        assertTrue(QuestionShape.isQuestion("should we restart the backend"));
        assertTrue(QuestionShape.isQuestion("What’s the difference between Watch and Alert"));
        assertFalse(QuestionShape.isQuestion("Turn off the heater"));
        assertFalse(QuestionShape.isQuestion("Turn off the heater?"), "a question mark alone is not a question");
        assertFalse(QuestionShape.isQuestion("Can you turn off the heater?"));
        assertFalse(QuestionShape.isQuestion("Could you silence this alert"));
        assertFalse(QuestionShape.isQuestion("Please arm the system"));
        assertFalse(QuestionShape.isQuestion("When the front door opens turn the heater on"), "a conditional automation is not a question");
        assertTrue(QuestionShape.isQuestion("Isn't the valve supposed to be closed"));
        assertFalse(QuestionShape.isQuestion("Whatever, turn it off"), "a word that merely starts with 'what'");
        assertFalse(QuestionShape.isQuestion("Isolate the garage sensor"), "a word that merely starts with 'is'");
        assertFalse(QuestionShape.isQuestion(""));
        assertFalse(QuestionShape.isQuestion(null));
    }

    // ── /ask: the Ask path has no way to propose ────────────────────────────

    @Test
    void theAskPathHoldsNothingThatProposesAndItsAnswerCarriesNoProposalOrTarget() {
        for (Class<?> type : List.of(TinyHelpdeskService.class, com.cabin.orchestrator.api.TinyHelpdeskController.class)) {
            for (Field f : type.getDeclaredFields()) {
                assertNotEquals(IntentProposalService.class, f.getType(), type.getSimpleName() + "." + f.getName());
                assertNotEquals(EntityResolver.class, f.getType(), type.getSimpleName() + "." + f.getName());
                assertNotEquals(AssistantInventoryService.class, f.getType(), type.getSimpleName() + "." + f.getName());
            }
        }
        List<String> answerFields = Arrays.stream(TinyHelpdeskAnswer.class.getRecordComponents()).map(c -> c.getName()).toList();
        assertEquals(List.of("question", "answer", "sources", "answeredByModel"), answerFields,
            "the Ask answer is text and citations only: no proposal, plan hash, step or entity id");
    }
}
