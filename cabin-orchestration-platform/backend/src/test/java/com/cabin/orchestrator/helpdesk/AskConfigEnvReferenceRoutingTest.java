package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W-117 C2 slice 1: the configuration reference for the environment names the guide did not name. A question that names one
 * of those settings (normal and failure phrasings for Blink, Vaultwarden and weather) reaches config_env_reference; asking
 * where a credential is still reaches credential_redaction, and everyday questions are not captured. The section is for
 * administrators only. The phrasings are written for this test from the guide section's own wording; none is a held-out or
 * sealed-set question.
 */
class AskConfigEnvReferenceRoutingTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final String CATEGORY = "config_env_reference";

    private AskContextBuilder builder;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.isDirectory(REPO_DOCS.resolve("ai-assistant/user-guide")),
            "repo docs/ not reachable from the test working directory");
        builder = new AskContextBuilder(new NoNodes(), new ReviewedDocumentSource(REPO_DOCS.toString()),
            new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    @Test
    void aQuestionNamingOneOfTheSettingsReachesTheReference() {
        List<String> questions = List.of(
            "What are BLINK_USERNAME and BLINK_PASSWORD for?",
            "Blink cameras stopped updating and BLINK_USERNAME and BLINK_PASSWORD are blank. What do I check?",
            "What is VAULTWARDEN_URL for?",
            "Platform import fails at the credential step; could the Vaultwarden URL be wrong?",
            "What does WEATHER_STATIONS control?",
            "I set WEATHER_ENABLED to false in .env but the weather job still runs.");
        questions.forEach(q -> assertThat(builder.categoryIdFor(q)).as(q).isEqualTo(CATEGORY));
    }

    @Test
    void credentialLocationAndEverydayQuestionsAreNotCaptured() {
        Map<String, String> unchanged = Map.of(
            "Where is the Blink password?", "credential_redaction",
            "Where is the Vaultwarden master password?", "credential_redaction");
        unchanged.forEach((q, category) -> assertThat(builder.categoryIdFor(q)).as(q).isEqualTo(category));
        for (String q : List.of("What's the Blink login?", "What's the outdoor weather at the cabin?", "The invite link doesn't open",
                "Why does my sensor say offline?", "How do I rename a device?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).doesNotContain(CATEGORY);
        }
    }

    @Test
    void onlyAnAdministratorReceivesTheReferenceSections() {
        String question = "I set WEATHER_ENABLED to false in .env but the weather job still runs.";
        assertThat(refs(question, HouseholdRole.ADMINISTRATOR)).anyMatch(r -> r.endsWith("#reference-weather"));
        assertThat(refs(question, HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).noneMatch(r -> r.contains("#reference-"));
        assertThat(refs(question, null)).noneMatch(r -> r.contains("#reference-"));
    }

    private List<String> refs(String question, HouseholdRole role) {
        return builder.buildContext(question, role).stream().map(KnowledgeNode::entityRef).toList();
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
