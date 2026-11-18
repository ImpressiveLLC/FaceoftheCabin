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

class AskCapabilityRoutesContextTest {
    private static final Path DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final String GUIDE = "ai-assistant/user-guide/capability-routes.md";
    private static final String REF = "doc:" + GUIDE + "#";

    private AskContextBuilder builder() {
        KnowledgeNodeRepository repository = mock(KnowledgeNodeRepository.class);
        when(repository.loadAll()).thenReturn(List.of());
        return new AskContextBuilder(repository, new ReviewedDocumentSource(DOCS.toString()),
            new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    private JsonNode cases() throws Exception {
        return new ObjectMapper().readTree(DOCS.resolve(
            "ai-assistant/corpus/capability-routes-cases-2026-11-18.json").toFile()).get("cases");
    }

    @Test
    void allCasesDeliverTheirEntireSectionWithoutExceedingBudget() throws Exception {
        AskContextBuilder builder = builder();
        ReviewedDocumentSource source = new ReviewedDocumentSource(DOCS.toString());
        for (JsonNode c : cases()) {
            String q = c.get("question").asText();
            String anchor = c.get("section").asText();
            assertThat(builder.categoryIdsFor(q)).as(q).contains(c.get("category").asText());
            List<KnowledgeNode> context = builder.buildContext(q, HouseholdRole.ADMINISTRATOR);
            KnowledgeNode n = context.stream().filter(x -> x.entityRef().equals(REF + anchor))
                .findFirst().orElseThrow(() -> new AssertionError("Missing requested section: " + q));
            assertThat(n.content()).as(q).isEqualTo(source.section(GUIDE, anchor).orElseThrow().text())
                .contains(c.get("expectedText").asText()).doesNotContain("[section truncated");
            assertThat(context.stream().filter(x -> x.entityRef().startsWith("doc:"))
                .mapToInt(x -> x.content().length()).sum()).as(q).isLessThanOrEqualTo(6000);
        }
    }

    @Test
    void noNonAdministratorReceivesCapabilityRouteSections() throws Exception {
        AskContextBuilder builder = builder();
        for (JsonNode c : cases()) {
            for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.ADULT_HOUSEHOLD_MEMBER,
                    HouseholdRole.CHILD, HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null}) {
                assertThat(builder.buildContext(c.get("question").asText(), role))
                    .noneMatch(n -> n.entityRef().startsWith(REF));
            }
        }
    }

    @Test
    void combinedCallerAndHelpdeskQuestionRetainsBothSections() {
        ReviewedDocumentSource source = new ReviewedDocumentSource(DOCS.toString());
        List<KnowledgeNode> context = builder().buildContext(
            "Explain caller boundaries and the helpdesk endpoint", HouseholdRole.ADMINISTRATOR);
        for (String section : List.of("caller-boundaries-and-read-exceptions", "ask-routes-and-proposal-prerequisites")) {
            assertThat(context).anySatisfy(n -> {
                assertThat(n.entityRef()).isEqualTo(REF + section);
                assertThat(n.content()).isEqualTo(source.section(GUIDE, section).orElseThrow().text());
            });
        }
        assertThat(context.stream().mapToInt(n -> n.content().length()).sum()).isLessThanOrEqualTo(6000);
    }

    @Test
    void routeWordsDoNotDisplaceCredentialGuidance() {
        AskContextBuilder builder = builder();
        String path = "ai-assistant/user-guide/configuration.md";
        String ref = "doc:" + path + "#credentials-and-access";
        String full = new ReviewedDocumentSource(DOCS.toString()).section(path, "credentials-and-access")
            .orElseThrow().text();
        for (String q : List.of("What is the helpdesk endpoint password?", "Where is the API base secret?",
                "What token do camera routes use?",
                "Can a new API base redirect my credentials to another origin?",
                "What password does the propose route require?", "What token does the propose route require?",
                "What secret does the propose route require?", "What password lets me call POST /api/ha/services?",
                "What token lets me call POST /api/ha/services?", "What secret lets me call POST /api/ha/services?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).contains("credential_redaction");
            assertThat(builder.buildContext(q, HouseholdRole.ADMINISTRATOR)).as(q).anySatisfy(n -> {
                assertThat(n.entityRef()).isEqualTo(ref); assertThat(n.content()).isEqualTo(full);
            });
        }
    }

    @Test
    void fixtureCopiesRemainByteIdentical() throws Exception {
        assertThat(Files.readAllBytes(DOCS.resolve("ai-assistant/rag/context-fixtures-r2.json")))
            .isEqualTo(Files.readAllBytes(Path.of("src/main/resources/rag/context-fixtures-r2.json")));
    }
}
