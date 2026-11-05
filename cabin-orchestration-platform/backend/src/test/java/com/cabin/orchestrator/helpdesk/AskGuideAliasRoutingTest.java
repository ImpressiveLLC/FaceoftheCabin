package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
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
 * W-151 part 2: curated guide-vocabulary phrases (device onboarding, capability map, minimum inputs, clean host,
 * knowledge nodes, citations, pull requests) route to the category whose section covers them. The phrasings are written
 * from the guide sections' own wording, not from any evaluation question, and none is a held-out or sealed-set question.
 */
class AskGuideAliasRoutingTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();

    private AskContextBuilder builder;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.isDirectory(REPO_DOCS.resolve("ai-assistant/user-guide")),
            "repo docs/ not reachable from the test working directory");
        KnowledgeNodeRepository none = new KnowledgeNodeRepository() {
            @Override public void upsert(KnowledgeNode node) { }
            @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
            @Override public List<KnowledgeNode> loadAll() { return List.of(); }
        };
        builder = new AskContextBuilder(none, new ReviewedDocumentSource(REPO_DOCS.toString()),
            new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    @Test
    void guideVocabularyReachesTheCategoryThatCoversIt() {
        Map<String, String> pinned = Map.of(
            "Walk me through device onboarding after a platform import.", "device_import_lifecycle",
            "Show me the capability map of the platform.", "live_sensors",
            "What are the minimum inputs to recreate an instance?", "installation",
            "Can I follow the guide on a fresh machine or a clean host?", "installation",
            "How does Ask cite a knowledge node?", "ask_helpdesk_behavior",
            "Who ratifies a documentation change made in a pull request?", "governance_contribution");
        pinned.forEach((question, category) ->
            assertThat(builder.categoryIdsFor(question)).as(question).contains(category));
    }

    @Test
    void theNewPhrasesDoNotPullOrdinaryQuestionsIntoTheirCategories() {
        // Unrelated everyday questions keep their existing first category; none of the new phrases may capture them.
        Map<String, String> unchanged = Map.of(
            "How do I rename a device?", "device_settings",
            "How do I add a new Zigbee sensor?", "device_add",
            "The disk is almost full, what do I do?", "storage_disk",
            "How do updates get deployed?", "deployment_source",
            "Why does my sensor say offline?", "device_health");
        unchanged.forEach((question, category) ->
            assertThat(builder.categoryIdFor(question)).as(question).isEqualTo(category));
    }
}
