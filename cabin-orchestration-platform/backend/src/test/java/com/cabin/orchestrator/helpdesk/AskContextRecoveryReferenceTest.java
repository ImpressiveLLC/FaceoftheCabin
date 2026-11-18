package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AskContextRecoveryReferenceTest {
    private static final Path DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final String GUIDE = "ai-assistant/user-guide/operations-recovery-reference.md";
    private static final String REF = "doc:" + GUIDE + "#";

    private AskContextBuilder builder() {
        KnowledgeNodeRepository repository = mock(KnowledgeNodeRepository.class);
        when(repository.loadAll()).thenReturn(List.of());
        return new AskContextBuilder(repository, new ReviewedDocumentSource(DOCS.toString()),
            new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    private JsonNode cases() throws Exception {
        return new ObjectMapper().readTree(DOCS.resolve(
            "ai-assistant/corpus/operations-recovery-cases-2026-11-18.json").toFile()).get("cases");
    }

    @Test
    void acceptanceQuestionsDeliverTheEntireExpectedSectionToAdministrators() throws Exception {
        AskContextBuilder builder = builder();
        ReviewedDocumentSource source = new ReviewedDocumentSource(DOCS.toString());
        for (JsonNode c : cases()) {
            String question = c.get("question").asText();
            String anchor = c.get("section").asText();
            assertThat(builder.categoryIdsFor(question)).as(question).contains(c.get("category").asText());
            List<KnowledgeNode> context = builder.buildContext(question, HouseholdRole.ADMINISTRATOR);
            KnowledgeNode expected = context.stream().filter(n -> n.entityRef().equals(REF + anchor))
                .findFirst().orElseThrow(() -> new AssertionError("Requested section missing: " + question));
            assertThat(expected.content()).as(question).isEqualTo(source.section(GUIDE, anchor).orElseThrow().text())
                .contains(c.get("expectedText").asText()).doesNotContain("[section truncated");
            assertThat(context.stream().filter(n -> n.entityRef().startsWith("doc:"))
                .mapToInt(n -> n.content().length()).sum()).as(question).isLessThanOrEqualTo(6000);
        }
    }

    @Test
    void everyNonAdministratorAndNullRoleGetsOnlyHouseholdRecoverySections() throws Exception {
        AskContextBuilder builder = builder();
        for (JsonNode c : cases()) {
            String question = c.get("question").asText();
            String expectedRef = REF + c.get("section").asText();
            for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.ADULT_HOUSEHOLD_MEMBER,
                    HouseholdRole.CHILD, HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null}) {
                List<KnowledgeNode> context = builder.buildContext(question, role);
                if (c.get("audience").asText().equals("household")) {
                    assertThat(context).as(question + " / " + role).extracting(KnowledgeNode::entityRef)
                        .contains(expectedRef);
                } else {
                    assertThat(context).as(question + " / " + role).extracting(KnowledgeNode::entityRef)
                        .doesNotContain(expectedRef);
                }
                assertThat(context.stream().filter(n -> n.entityRef().startsWith(REF))
                    .map(KnowledgeNode::entityRef)).allMatch(ref ->
                        ref.equals(REF + "incident-triage-for-the-household")
                        || ref.equals(REF + "grafana-login-outside-the-embedded-panel"));
            }
        }
    }

    @Test
    void tiedKeyAndDeadmanQuestionRetainsBothRequestedSections() {
        List<KnowledgeNode> context = builder().buildContext(
            "Explain monitoring key and external heartbeat", HouseholdRole.ADMINISTRATOR);
        assertThat(context).extracting(KnowledgeNode::entityRef).contains(
            REF + "monitoring-key-access", REF + "external-dead-man-monitoring");
        assertThat(context).allSatisfy(n -> assertThat(n.content()).doesNotContain("[section truncated"));
    }

    @Test
    void credentialQuestionsKeepTheCompleteCredentialGuidance() {
        AskContextBuilder builder = builder();
        String path = "ai-assistant/user-guide/configuration.md";
        String ref = "doc:" + path + "#credentials-and-access";
        String full = new ReviewedDocumentSource(DOCS.toString())
            .section(path, "credentials-and-access").orElseThrow().text();
        for (String q : List.of("What is the Node-RED login password?",
                "Where do I find the Grafana login password?", "What is the external heartbeat token?",
                "Where is the monitoring key secret?", "What password is used for storage pruning?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).contains("credential_redaction");
            assertThat(builder.buildContext(q, HouseholdRole.ADMINISTRATOR)).as(q)
                .anySatisfy(n -> { assertThat(n.entityRef()).isEqualTo(ref);
                    assertThat(n.content()).isEqualTo(full); });
        }
    }

    @Test
    void editorAndExistingLoginAliasesDoNotHideCredentialGuidance() {
        AskContextBuilder builder = builder();
        String path = "ai-assistant/user-guide/configuration.md";
        String ref = "doc:" + path + "#credentials-and-access";
        String full = new ReviewedDocumentSource(DOCS.toString())
            .section(path, "credentials-and-access").orElseThrow().text();
        for (String credential : List.of("password", "token", "secret")) {
            for (String question : List.of("Where is the " + credential + " for the Node-RED editor?",
                    "What " + credential + " is used for Node-RED login in the Node-RED editor?",
                    "What " + credential + " is needed for Node-RED password rotation in the Node-RED editor?")) {
                List<KnowledgeNode> context = builder.buildContext(question, HouseholdRole.ADMINISTRATOR);
                assertThat(context).as(question).anySatisfy(n -> {
                    assertThat(n.entityRef()).isEqualTo(ref);
                    assertThat(n.content()).isEqualTo(full);
                });
                assertThat(context.stream().mapToInt(n -> n.content().length()).sum())
                    .as(question).isLessThanOrEqualTo(6000);
            }
        }
    }

    @Test
    void fixtureCopiesRemainByteIdentical() throws Exception {
        assertThat(Files.readAllBytes(DOCS.resolve("ai-assistant/rag/context-fixtures-r2.json")))
            .isEqualTo(Files.readAllBytes(Path.of("src/main/resources/rag/context-fixtures-r2.json")));
    }
}
