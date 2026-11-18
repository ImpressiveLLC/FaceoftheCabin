package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * C1-S1 delivery harness (W-117 C1, Cowork ruling #244 6979177326). For every row of the development retrieval set it runs
 * the real AskContextBuilder with no knowledge nodes, the reviewed docs and the 6000-character budget, and records whether
 * the expected guide section reaches the row's audience whole. No model, network or database.
 *
 * <p>The development set is spent by design and is never acceptance evidence. The gates freeze the baseline rather than
 * set a target: every row not marked as a known gap must keep delivering its section, every known gap must still be one
 * (a fix must clear its knownGap marker in the same change, so the before/after is visible), expected sections must
 * resolve, and no administrator section may reach any other role. The report is written to
 * target/c1-s1-delivery-report.json.
 */
class AskDeliveryHarnessTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final Path DEV_SET = REPO_DOCS.resolve("ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json");
    private static final Path FIXTURES = REPO_DOCS.resolve("ai-assistant/rag/context-fixtures-r2.json");
    private static final Path REPORT = Path.of("target/c1-s1-delivery-report.json");
    private static final int BUDGET = 6000;
    private static final String TRUNCATED = "section truncated";
    /** Every non-administrator role the builder distinguishes, plus no role at all. */
    private static final List<HouseholdRole> OTHER_ROLES = Arrays.asList(
        HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD, HouseholdRole.KIOSK_DISPLAY, null);

    private AskContextBuilder builder;
    private ReviewedDocumentSource documents;
    private JsonNode rows;
    private final Set<String> adminOnlySections = new java.util.HashSet<>();

    @BeforeEach
    void setUp() throws IOException {
        assumeTrue(Files.isRegularFile(DEV_SET) && Files.isRegularFile(FIXTURES), "repo docs/ not reachable from the test working directory");
        documents = new ReviewedDocumentSource(REPO_DOCS.toString());
        builder = new AskContextBuilder(new NoNodes(), documents, new DefaultResourceLoader(),
            "classpath:rag/context-fixtures-r2.json", BUDGET);
        ObjectMapper json = new ObjectMapper();
        rows = json.readTree(DEV_SET.toFile()).get("rows");
        Map<String, Set<String>> audiences = new HashMap<>();
        for (JsonNode category : json.readTree(FIXTURES.toFile()).get("categories")) {
            for (JsonNode doc : category.path("contextDocuments")) {
                audiences.computeIfAbsent(ref(doc.get("path").asText(), doc.get("section").asText()), k -> new java.util.HashSet<>())
                    .add(doc.path("audience").asText("administrator"));
            }
        }
        audiences.forEach((section, set) -> { if (!set.contains("household")) adminOnlySections.add(section); });
    }

    @Test
    void theDevelopmentSetIsMeasuredAndTheBaselineHolds() throws IOException {
        List<Map<String, Object>> results = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        List<String> gapNowDelivered = new ArrayList<>();
        Map<String, int[]> perAudience = new TreeMap<>();

        for (JsonNode row : rows) {
            String id = row.get("id").asText();
            String question = row.get("question").asText();
            String expectedCategory = row.get("expectedCategory").asText();
            String expectedSection = row.get("expectedSection").asText();
            String audience = row.get("audience").asText();
            boolean knownGap = row.has("knownGap");

            String[] parts = expectedSection.split("#", 2);
            if (documents.section(parts[0], parts[1]).isEmpty()) unresolved.add(id + " " + expectedSection);

            HouseholdRole role = "household".equals(audience) ? HouseholdRole.ADULT_HOUSEHOLD_MEMBER : HouseholdRole.ADMINISTRATOR;
            String first = builder.categoryIdFor(question);
            List<String> selected = builder.categoryIdsFor(question);
            List<KnowledgeNode> nodes = builder.buildContext(question, role);
            List<String> delivered = new ArrayList<>();
            List<String> truncated = new ArrayList<>();
            boolean wholeExpected = false;
            int chars = 0;
            for (KnowledgeNode node : nodes) {
                String section = node.entityRef().replaceFirst("^doc:", "");
                delivered.add(section);
                chars += node.content().length();
                boolean whole = !node.content().contains(TRUNCATED);
                if (!whole) truncated.add(section);
                if (section.equals(expectedSection) && whole) wholeExpected = true;
            }
            boolean tieNotDelivered = selected.contains(expectedCategory) && !wholeExpected;

            for (HouseholdRole other : OTHER_ROLES) {
                for (KnowledgeNode node : builder.buildContext(question, other)) {
                    String section = node.entityRef().replaceFirst("^doc:", "");
                    if (adminOnlySections.contains(section)) leaks.add(id + " " + other + " " + section);
                }
            }
            if (!knownGap && !wholeExpected) regressions.add(id + " expected " + expectedSection + " got " + delivered);
            if (knownGap && wholeExpected) gapNowDelivered.add(id);

            int[] counts = perAudience.computeIfAbsent(audience, k -> new int[6]);
            counts[0]++;
            if (expectedCategory.equals(first)) counts[1]++;
            if (selected.contains(expectedCategory)) counts[2]++;
            if (wholeExpected) counts[3]++;
            if (first == null) counts[4]++;
            if (tieNotDelivered) counts[5]++;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("audience", audience);
            result.put("expectedCategory", expectedCategory);
            result.put("expectedSection", expectedSection);
            result.put("firstCategory", first);
            result.put("selectedCategories", selected);
            result.put("deliveredSections", delivered);
            result.put("truncatedSections", truncated);
            result.put("deliveredChars", chars);
            result.put("expectedDeliveredWhole", wholeExpected);
            result.put("expectedSelectedButNotDelivered", tieNotDelivered);
            result.put("knownGap", knownGap ? row.get("knownGap").asText() : null);
            results.add(result);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        perAudience.forEach((audience, c) -> summary.put(audience, Map.of(
            "rows", c[0], "firstCategoryAgrees", c[1], "expectedInSelectedSet", c[2],
            "expectedDeliveredWhole", c[3], "noCategory", c[4], "selectedButNotDelivered", c[5])));
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("budgetChars", BUDGET);
        report.put("devSet", "docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json (development, spent; never acceptance)");
        report.put("summaryByAudience", summary);
        report.put("audienceLeaks", leaks.size());
        report.put("results", results);
        Files.createDirectories(REPORT.toAbsolutePath().getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(REPORT.toFile(), report);
        System.out.println("C1-S1 delivery summary " + summary + ", audienceLeaks=" + leaks.size());

        assertThat(unresolved).as("expected sections that no longer resolve").isEmpty();
        assertThat(leaks).as("administrator sections reaching another role").isEmpty();
        assertThat(regressions).as("baseline rows that stopped delivering their section whole").isEmpty();
        assertThat(gapNowDelivered).as("known gaps now delivered: clear their knownGap marker in the fixing change").isEmpty();
    }

    private static String ref(String path, String section) {
        return path + "#" + section;
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
