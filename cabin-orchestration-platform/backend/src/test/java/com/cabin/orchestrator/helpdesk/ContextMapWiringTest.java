package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C2 reachability: a guide section reaches Ask only through the context map, and the reviewed
 * documents a category adds are capped by a per-question character budget. These tests keep the map
 * and the guides from drifting apart: every mapped section exists, each category's primary section
 * fits whole, and a category that exceeds the budget is a named, reviewed exception.
 */
class ContextMapWiringTest {

    private static final String FIXTURES = "classpath:rag/context-fixtures-r2.json";
    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final Path FIXTURE_COPY = REPO_DOCS.resolve("ai-assistant/rag/context-fixtures-r2.json");
    private static final int BUDGET = 6000;
    /**
     * Categories allowed to exceed the budget, which cuts a later section. Empty on purpose: every category fits
     * whole. An entry here needs a review because it silently drops guide text from the answer.
     */
    private static final Set<String> KNOWN_OVER_BUDGET = Set.of();

    private static final ObjectMapper JSON = new ObjectMapper();

    private ReviewedDocumentSource source;
    private AskContextBuilder builder;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.isRegularFile(FIXTURE_COPY) && Files.isDirectory(REPO_DOCS.resolve("ai-assistant/user-guide")),
            "repo docs/ not reachable from the test working directory");
        source = new ReviewedDocumentSource(REPO_DOCS.toString());
        KnowledgeNodeRepository none = new FakeKnowledgeNodeRepository();
        builder = new AskContextBuilder(none, source, new DefaultResourceLoader(), FIXTURES, BUDGET);
    }

    private static JsonNode categories() throws IOException {
        return JSON.readTree(FIXTURE_COPY.toFile()).get("categories");
    }

    @Test
    void everyMappedSectionExistsInItsGuide() throws IOException {
        List<String> missing = new ArrayList<>();
        for (JsonNode category : categories()) {
            for (JsonNode ref : category.path("contextDocuments")) {
                String path = ref.get("path").asText();
                String section = ref.get("section").asText();
                if (source.section(path, section).isEmpty()) {
                    missing.add(category.get("id").asText() + " -> " + path + "#" + section);
                }
            }
        }
        assertThat(missing).as("mapped sections with no matching heading").isEmpty();
    }

    @Test
    void eachCategoryPrimarySectionFitsTheBudgetWhole() throws IOException {
        List<String> tooBig = new ArrayList<>();
        for (JsonNode category : categories()) {
            JsonNode docs = category.path("contextDocuments");
            if (docs.isEmpty()) continue;
            int size = source.section(docs.get(0).get("path").asText(), docs.get(0).get("section").asText())
                .map(s -> s.text().length()).orElse(0);
            if (size > BUDGET) tooBig.add(category.get("id").asText() + " first section " + size);
        }
        assertThat(tooBig).as("a primary section longer than the budget would be cut mid-answer").isEmpty();
    }

    @Test
    void aCategoryOverTheBudgetIsAKnownException() throws IOException {
        List<String> unexpected = new ArrayList<>();
        for (JsonNode category : categories()) {
            int total = 0;
            for (JsonNode ref : category.path("contextDocuments")) {
                total += source.section(ref.get("path").asText(), ref.get("section").asText())
                    .map(s -> s.text().length()).orElse(0);
            }
            String id = category.get("id").asText();
            if (total > BUDGET && !KNOWN_OVER_BUDGET.contains(id)) unexpected.add(id + " " + total);
        }
        assertThat(unexpected).as("categories whose reviewed sections exceed " + BUDGET + " chars").isEmpty();
    }

    @Test
    void theNewC2QuestionsRouteToTheirCategories() {
        Map<String, String> expected = new java.util.LinkedHashMap<>();
        expected.put("HA_TOKEN is nonempty and I changed POSTGRES_PASSWORD in .env; is authentication now fixed?", "configuration_binding");
        expected.put("Is my HA_TOKEN valid when the health check says UP?", "configuration_binding");
        expected.put("How is the POSTGRES_PASSWORD rotation done?", "configuration_binding");
        expected.put("I changed the UI API URL in the running container. Why is the browser still using the old URL?", "config_build_time_binding");
        expected.put("Does the platform have autoheal, or restart an unhealthy container on its own?", "self_healing_recovery");
        expected.put("How do I back up the database? Is there a pg_dump job?", "backup_restore_recipes");
        expected.put("Why is STORAGE_WARN_PERCENT in .env ignored? Is it passed through to the container?", "config_passthrough");
        expected.put("What num_ctx does Ollama use for Ask?", "ask_runtime_limits");
        expected.put("Every service says running. What should I verify?", "health_verification");
        expected.put("The page cannot load. What do I check first?", "health_triage");
        expected.put("Which triggers can a workflow use, and can it lock a door?", "rules_workflows");
        expected.put("Who counts as present: a primary user or a maintenance user?", "presence_roles_and_gaps");
        expected.put("How do I set up automatic presence at a second location?", "installation_presence");
        expected.put("An opportunity is listed. Has the suggested enhancement been implemented?", "opportunity_status");
        expected.put("All containers restarted successfully on the replacement host. Is recovery complete?", "persistence_recovery");
        expected.forEach((question, category) ->
            assertThat(builder.categoryIdFor(question)).as(question).isEqualTo(category));
    }

    @Test
    void theServiceHealthTableIsSuppliedFirstAndWholeToAnAdministrator() {
        List<KnowledgeNode> context = builder.buildContext("Every service says running. What should I verify?",
            HouseholdRole.ADMINISTRATOR);

        assertThat(context).isNotEmpty();
        assertThat(context).extracting(KnowledgeNode::entityRef).containsExactly(
            "doc:ai-assistant/user-guide/maintenance.md#service-health-at-a-glance",
            "doc:ai-assistant/user-guide/maintenance.md#running-versus-healthy-per-service-k11k12");
        assertThat(context).allSatisfy(n -> assertThat(n.content()).doesNotContain("[section truncated"));
        assertThat(context.stream().mapToInt(n -> n.content().length()).sum()).isLessThanOrEqualTo(BUDGET + 200);
    }

    @Test
    void aNonAdministratorGetsNoAdministratorSectionsFromTheNewCategories() {
        for (String question : List.of(
                "Is my HA_TOKEN valid when the health check says UP?",
                "How do I back up the database? Is there a pg_dump job?",
                "Does the platform have autoheal, or restart an unhealthy container on its own?")) {
            assertThat(builder.buildContext(question, HouseholdRole.CHILD)).as(question).isEmpty();
            assertThat(builder.buildContext(question, null)).as(question).isEmpty();
        }
    }

    @Test
    void theRewriteMapChangesAreInPlace() throws IOException {
        JsonNode installation = category("installation");
        JsonNode presence = category("installation_presence");
        JsonNode live = category("live_sensors");
        assertThat(sections(installation)).containsExactly("minimum-inputs", "verified-clean-host-limitations");
        assertThat(sections(presence)).containsExactly("presence-for-each-location");
        assertThat(sections(live).get(0)).isEqualTo("live-values-and-ask");
        // ties go to declaration order, so the presence category must come after installation
        List<String> order = new ArrayList<>();
        categories().forEach(c -> order.add(c.get("id").asText()));
        assertThat(order.indexOf("installation_presence")).isEqualTo(order.indexOf("installation") + 1);
    }

    @Test
    void everyFrozenQuestionStillRoutesToACategoryThatNamesIt() throws IOException {
        JsonNode questions = JSON.readTree(REPO_DOCS.resolve("ai-assistant/rag/cli-questions-r2.json").toFile()).get("questions");
        List<String> misrouted = new ArrayList<>();
        for (JsonNode q : questions) {
            String id = q.get("id").asText();
            String routed = builder.categoryIdFor(q.get("question").asText());
            boolean named = false;
            for (JsonNode c : categories()) {
                if (c.get("id").asText().equals(routed)) {
                    for (JsonNode named_id : c.path("questions")) named |= named_id.asText().equals(id);
                }
            }
            if (!named) misrouted.add(id + " -> " + routed);
        }
        assertThat(misrouted).as("frozen questions that no longer route to a category naming them").isEmpty();
    }

    private static JsonNode category(String id) throws IOException {
        for (JsonNode c : categories()) if (c.get("id").asText().equals(id)) return c;
        throw new AssertionError("no category " + id);
    }

    private static List<String> sections(JsonNode category) {
        List<String> out = new ArrayList<>();
        category.path("contextDocuments").forEach(d -> out.add(d.get("section").asText()));
        return out;
    }

    private static final class FakeKnowledgeNodeRepository implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
