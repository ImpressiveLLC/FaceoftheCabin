package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class AskContextBuilderTest {

    private static final String FIXTURES = "classpath:rag/context-fixtures-r2.json";
    /** Surefire runs with the backend module as cwd, so the repo's docs/ is two levels up. */
    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final Path FIXTURE_COPY = REPO_DOCS.resolve("ai-assistant/rag/context-fixtures-r2.json");
    private static final Path FROZEN_QUESTIONS = REPO_DOCS.resolve("ai-assistant/rag/cli-questions-r1.json");

    private static final ObjectMapper JSON = new ObjectMapper();

    private FakeKnowledgeNodeRepository repository;
    private AskContextBuilder nodesOnly;
    private AskContextBuilder withDocs;

    @BeforeEach
    void setUp() {
        repository = new FakeKnowledgeNodeRepository();
        addNode(repository, "system.presence", KnowledgeChunkType.DESCRIPTION, "Presence description.");
        addNode(repository, "system.presence", KnowledgeChunkType.TROUBLESHOOTING, "Presence troubleshooting.");
        addNode(repository, "system.sensors", KnowledgeChunkType.DESCRIPTION, "Sensors description.");
        addNode(repository, "system.cameras", KnowledgeChunkType.DESCRIPTION, "Cameras description.");
        addNode(repository, "system.cameras", KnowledgeChunkType.TROUBLESHOOTING, "Cameras troubleshooting.");
        // Category nodes only: a docs root that does not exist disables document context.
        nodesOnly = builder(repository, REPO_DOCS.resolve("no-such-docs-root"), 6000);
        withDocs = builder(new FakeKnowledgeNodeRepository(), REPO_DOCS, 6000);
    }

    private static AskContextBuilder builder(KnowledgeNodeRepository repo, Path docsRoot, int budget) {
        return new AskContextBuilder(repo, new ReviewedDocumentSource(docsRoot.toString()),
            new DefaultResourceLoader(), FIXTURES, budget);
    }

    private void requireRepoDocs() {
        assumeTrue(Files.isDirectory(REPO_DOCS.resolve("ai-assistant/user-guide")),
            "repo docs/ not reachable from the test working directory");
    }

    // ---- category nodes (unchanged r1 behavior) ------------------------------------------------

    @Test
    void liveSensorQuestion_returnsSensorAndPresenceNodes() {
        List<KnowledgeNode> result = nodesOnly.buildContext("What is the temperature right now?");

        assertThat(result.stream().map(KnowledgeNode::entityRef)).contains("system.sensors", "system.presence");
    }

    @Test
    void cameraQuestion_returnsCameraNodes() {
        List<KnowledgeNode> result = nodesOnly.buildContext(
            "My camera card is empty -- does that mean nobody was home?");

        assertThat(result.stream().map(KnowledgeNode::entityRef)).contains("system.cameras");
    }

    @Test
    void unrecognizedQuestion_returnsEmptyList() {
        assertThat(nodesOnly.buildContext("What is the weather in Paris?")).isEmpty();
    }

    @Test
    void missingNode_isSkippedNotFailed() {
        FakeKnowledgeNodeRepository sparse = new FakeKnowledgeNodeRepository();
        addNode(sparse, "system.cameras", KnowledgeChunkType.DESCRIPTION, "Cameras description.");
        AskContextBuilder sparseBuilder = builder(sparse, REPO_DOCS.resolve("no-such-docs-root"), 6000);

        List<KnowledgeNode> result = sparseBuilder.buildContext("camera card is empty");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).entityRef()).isEqualTo("system.cameras");
    }

    // ---- routing (r1 first-substring bugs) ------------------------------------------------------

    @Test
    void frozenQuestions_eachRouteToACategoryThatDeclaresThem() throws IOException {
        assumeTrue(Files.isRegularFile(FROZEN_QUESTIONS) && Files.isRegularFile(FIXTURE_COPY),
            "frozen question set not reachable from the test working directory");
        Map<String, Set<String>> declared = new HashMap<>();
        for (JsonNode category : JSON.readTree(FIXTURE_COPY.toFile()).get("categories")) {
            for (JsonNode q : category.get("questions")) {
                declared.computeIfAbsent(q.asText(), k -> new HashSet<>()).add(category.get("id").asText());
            }
        }

        List<String> misrouted = new ArrayList<>();
        for (JsonNode q : JSON.readTree(FROZEN_QUESTIONS.toFile()).get("questions")) {
            String id = q.get("id").asText();
            String routed = nodesOnly.categoryIdFor(q.get("question").asText());
            if (routed == null || !declared.getOrDefault(id, Set.of()).contains(routed)) {
                misrouted.add(id + "->" + routed);
            }
        }

        assertThat(misrouted).as("frozen questions routed outside their declared category").isEmpty();
    }

    @Test
    void r1Misroutes_areFixed() {
        // r1 substring matching: 'auth' <- authorize/authentication, 'household role' <- roles,
        // 'run' <- running, 'current' <- "current Ask endpoint".
        assertThat(nodesOnly.categoryIdFor(
            "Presence says away. Does that prove the home is empty or authorize disarming?"))
            .isEqualTo("live_sensors");
        assertThat(nodesOnly.categoryIdFor(
            "How should Ask answer credential questions for administrators versus other household roles?"))
            .isEqualTo("credential_redaction");
        assertThat(nodesOnly.categoryIdFor(
            "HA_TOKEN is nonempty and I changed POSTGRES_PASSWORD in .env; is authentication now fixed?"))
            .isEqualTo("configuration_binding");
        assertThat(nodesOnly.categoryIdFor("Every service says running. What should I verify?"))
            .isEqualTo("health_verification");
        assertThat(nodesOnly.categoryIdFor(
            "Will merging these Markdown guides make the current Ask endpoint know them?"))
            .isEqualTo("ask_helpdesk_behavior");
    }

    @Test
    void patternsMatchWholeWordsOnly() {
        assertThat(nodesOnly.categoryIdFor("Please authorize this")).isNull();
        assertThat(nodesOnly.categoryIdFor("Running low on disk space")).isNull();
        assertThat(nodesOnly.categoryIdFor("What is the current price of milk?")).isNull();
        assertThat(nodesOnly.categoryIdFor("Do notes sync between phones?")).isEqualTo("note_sync");
    }

    @Test
    void credentialQuestionsAreBoostedOverACompetingCategory() {
        // 'family profile' alone scores 2 for user_auth_role; the credential boost (1 + 2) keeps a
        // question about credentials on the role-gated credential guidance.
        assertThat(nodesOnly.categoryIdFor("Can my family profile see the stored credentials?"))
            .isEqualTo("credential_redaction");
    }

    // ---- reviewed documents ---------------------------------------------------------------------

    @Test
    void everyFixtureDocumentReference_resolvesToARealSection() throws IOException {
        requireRepoDocs();
        assumeTrue(Files.isRegularFile(FIXTURE_COPY));
        ReviewedDocumentSource source = new ReviewedDocumentSource(REPO_DOCS.toString());

        List<String> unresolved = new ArrayList<>();
        for (JsonNode category : JSON.readTree(FIXTURE_COPY.toFile()).get("categories")) {
            for (JsonNode doc : category.get("contextDocuments")) {
                if (source.section(doc.get("path").asText(), doc.get("section").asText()).isEmpty()) {
                    unresolved.add(category.get("id").asText() + " -> " + doc.get("path").asText()
                        + "#" + doc.get("section").asText());
                }
            }
        }

        assertThat(unresolved).as("fixture references with no matching doc heading (renamed or removed?)").isEmpty();
    }

    @Test
    void theClasspathFixtureMatchesTheDocsCopy() throws IOException {
        assumeTrue(Files.isRegularFile(FIXTURE_COPY));
        try (var in = new DefaultResourceLoader().getResource(FIXTURES).getInputStream()) {
            assertThat(JSON.readTree(in)).isEqualTo(JSON.readTree(FIXTURE_COPY.toFile()));
        }
    }

    @Test
    void administratorGetsTheSectionTextAsAReviewedDocumentNode() {
        requireRepoDocs();

        List<KnowledgeNode> result = withDocs.buildContext(
            "Can I run the M920q overlay unchanged on a blank machine?", HouseholdRole.ADMINISTRATOR);

        assertThat(result).isNotEmpty();
        KnowledgeNode first = result.get(0);
        assertThat(first.entityRef()).isEqualTo("doc:ai-assistant/user-guide/independent-installation.md#minimum-inputs");
        assertThat(first.source()).isEqualTo(KnowledgeSource.REVIEWED_DOCUMENT);
        assertThat(first.content()).startsWith("## Minimum inputs").doesNotContain("## Establish the actual installation plan");
    }

    @Test
    void nonAdministratorsAndNoRoleGetNoAdministratorAudienceDocuments() {
        requireRepoDocs();
        String question = "Can I run the M920q overlay unchanged on a blank machine?";

        assertThat(withDocs.buildContext(question, HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).isEmpty();
        assertThat(withDocs.buildContext(question, HouseholdRole.CHILD)).isEmpty();
        assertThat(withDocs.buildContext(question, HouseholdRole.KIOSK_DISPLAY)).isEmpty();
        assertThat(withDocs.buildContext(question, HouseholdRole.SERVICE)).isEmpty();
        assertThat(withDocs.buildContext(question, null)).isEmpty();
        assertThat(withDocs.buildContext(question)).isEmpty();
    }

    @Test
    void householdAudienceDocumentsReachEveryRole() {
        requireRepoDocs();

        List<KnowledgeNode> result = withDocs.buildContext(
            "The camera card is empty. Does that mean nobody was there?", HouseholdRole.CHILD);

        assertThat(result).extracting(KnowledgeNode::entityRef)
            .containsExactly("doc:ai-assistant/user-guide/operations.md#capability-map");
    }

    @Test
    void documentContextIsCappedByTheBudgetAndSaysWhenItTruncates() {
        requireRepoDocs();
        AskContextBuilder tight = builder(new FakeKnowledgeNodeRepository(), REPO_DOCS, 500);

        List<KnowledgeNode> result = tight.buildContext(
            "Can I run the M920q overlay unchanged on a blank machine?", HouseholdRole.ADMINISTRATOR);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).content()).contains("[section truncated to fit the context budget");
        assertThat(result.get(0).content().length()).isLessThan(500 + 200);
    }

    @Test
    void documentNodesComeAfterCuratedNodes() {
        requireRepoDocs();
        FakeKnowledgeNodeRepository both = new FakeKnowledgeNodeRepository();
        addNode(both, "system.cameras", KnowledgeChunkType.DESCRIPTION, "Cameras description.");
        AskContextBuilder builder = builder(both, REPO_DOCS, 6000);

        List<KnowledgeNode> result = builder.buildContext("The camera card is empty", HouseholdRole.ADMINISTRATOR);

        assertThat(result).extracting(KnowledgeNode::entityRef).containsExactly(
            "system.cameras", "doc:ai-assistant/user-guide/operations.md#capability-map");
    }

    // ---------------------------------------------------------------------------------------------

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
