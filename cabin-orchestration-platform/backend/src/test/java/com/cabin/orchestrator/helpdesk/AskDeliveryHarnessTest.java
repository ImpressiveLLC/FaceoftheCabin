package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * C1-S1 delivery harness (W-117 C1, Cowork ruling #244 6979177326). For every row of the development retrieval set it runs
 * the real AskContextBuilder with no knowledge nodes, the reviewed docs and the 6000-character budget, and records whether
 * the expected guide section reaches the row's audience whole. No model, network or database.
 *
 * <p>The development set is spent by design and is never acceptance evidence. The gates freeze the baseline rather than set
 * a target. Inputs fail closed: a missing file, an empty or malformed set, a duplicate id or an unknown audience fails the
 * build instead of skipping. "Whole" means the delivered text equals the reviewed section's text exactly. The document
 * budget is enforced for every row and role. Audience policy comes from the development rows themselves, independently of
 * the routing map, and a routing-map audience that contradicts it fails. The report records the measured revision and
 * fingerprints of every input it consumed. Gate logic is checked directly with synthetic cases below.
 */
class AskDeliveryHarnessTest {

    private static final Path REPO_ROOT = Path.of("../..").toAbsolutePath().normalize();
    private static final Path REPO_DOCS = REPO_ROOT.resolve("docs");
    private static final String DEV_SET_REL = "docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json";
    private static final String FIXTURE_DOCS_REL = "docs/ai-assistant/rag/context-fixtures-r2.json";
    private static final String FIXTURE_BACKEND_REL = "cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json";
    private static final String PHRASINGS_REL = "docs/ai-assistant/corpus/ask-coverage-tools/phrasings.json";
    private static final Path REPORT = Path.of("target/c1-s1-delivery-report.json");
    static final int BUDGET = 6000;
    static final Set<String> AUDIENCES = Set.of("administrator", "household");
    /** Every role the builder distinguishes other than ADMINISTRATOR, plus no role at all. */
    static final List<HouseholdRole> OTHER_ROLES = Arrays.asList(
        HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD, HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null);

    record Row(String id, String question, String expectedCategory, String expectedSection, String audience, String knownGap) {}

    @Test
    void theDevelopmentSetIsMeasuredAndTheBaselineHolds() throws IOException {
        Path devSet = REPO_ROOT.resolve(DEV_SET_REL);
        Path fixture = REPO_ROOT.resolve(FIXTURE_DOCS_REL);
        assertThat(devSet).as("development set is required").isRegularFile();
        assertThat(fixture).as("routing map is required").isRegularFile();
        assertThat(REPO_DOCS.resolve("ai-assistant/user-guide")).as("reviewed guide is required").isDirectory();

        ObjectMapper json = new ObjectMapper();
        List<Row> rows = validateRows(json.readTree(devSet.toFile()));
        Map<String, String> policy = audiencePolicy(rows);
        Map<String, Set<String>> mapAudiences = fixtureAudiences(json.readTree(fixture.toFile()));
        assertThat(contradictions(policy, mapAudiences)).as("routing-map audiences that contradict the development set's policy").isEmpty();
        Set<String> adminOnly = new HashSet<>();
        mapAudiences.forEach((section, set) -> { if (!set.contains("household")) adminOnly.add(section); });
        policy.forEach((section, audience) -> { if ("administrator".equals(audience)) adminOnly.add(section); });

        ReviewedDocumentSource documents = new ReviewedDocumentSource(REPO_DOCS.toString());
        AskContextBuilder builder = new AskContextBuilder(new NoNodes(), documents, new DefaultResourceLoader(),
            "classpath:rag/context-fixtures-r2.json", BUDGET);

        List<Map<String, Object>> results = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        List<String> leaks = new ArrayList<>();
        List<String> overBudget = new ArrayList<>();
        List<String> regressions = new ArrayList<>();
        List<String> gapNowDelivered = new ArrayList<>();
        Set<String> consumedGuides = new TreeSet<>();
        Map<String, int[]> perAudience = new TreeMap<>();

        for (Row row : rows) {
            String[] parts = row.expectedSection().split("#", 2);
            consumedGuides.add(parts[0]);
            String expectedText = documents.section(parts[0], parts[1]).map(ReviewedDocumentSource.DocumentSection::text).orElse(null);
            if (expectedText == null) unresolved.add(row.id() + " " + row.expectedSection());

            HouseholdRole role = "household".equals(row.audience()) ? HouseholdRole.ADULT_HOUSEHOLD_MEMBER : HouseholdRole.ADMINISTRATOR;
            String first = builder.categoryIdFor(row.question());
            List<String> selected = builder.categoryIdsFor(row.question());
            List<KnowledgeNode> nodes = builder.buildContext(row.question(), role);
            List<String> delivered = new ArrayList<>();
            List<String> notWhole = new ArrayList<>();
            boolean wholeExpected = false;
            for (KnowledgeNode node : nodes) {
                String section = sectionOf(node);
                delivered.add(section);
                consumedGuides.add(section.split("#", 2)[0]);
                String[] p = section.split("#", 2);
                String source = p.length == 2 ? documents.section(p[0], p[1]).map(ReviewedDocumentSource.DocumentSection::text).orElse(null) : null;
                if (!deliveredWhole(node, source)) notWhole.add(section);
                if (section.equals(row.expectedSection()) && deliveredWhole(node, expectedText)) wholeExpected = true;
            }
            int chars = totalChars(nodes);
            if (chars > BUDGET) overBudget.add(row.id() + " " + role + " " + chars);
            boolean selectedNotDelivered = selected.contains(row.expectedCategory()) && !wholeExpected;

            for (HouseholdRole other : OTHER_ROLES) {
                List<KnowledgeNode> otherNodes = builder.buildContext(row.question(), other);
                if (totalChars(otherNodes) > BUDGET) overBudget.add(row.id() + " " + other + " " + totalChars(otherNodes));
                leaks.addAll(leaksFor(row.id(), other, otherNodes, adminOnly));
            }
            if (row.knownGap() == null && !wholeExpected) regressions.add(row.id() + " expected " + row.expectedSection() + " got " + delivered);
            if (row.knownGap() != null && wholeExpected) gapNowDelivered.add(row.id());

            int[] c = perAudience.computeIfAbsent(row.audience(), k -> new int[6]);
            c[0]++;
            if (row.expectedCategory().equals(first)) c[1]++;
            if (selected.contains(row.expectedCategory())) c[2]++;
            if (wholeExpected) c[3]++;
            if (first == null) c[4]++;
            if (selectedNotDelivered) c[5]++;

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", row.id());
            r.put("audience", row.audience());
            r.put("expectedCategory", row.expectedCategory());
            r.put("expectedSection", row.expectedSection());
            r.put("firstCategory", first);
            r.put("selectedCategories", selected);
            r.put("deliveredSections", delivered);
            r.put("sectionsNotDeliveredWhole", notWhole);
            r.put("deliveredChars", chars);
            r.put("expectedDeliveredWhole", wholeExpected);
            r.put("expectedSelectedButNotDelivered", selectedNotDelivered);
            r.put("knownGap", row.knownGap());
            results.add(r);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        perAudience.forEach((a, c) -> summary.put(a, Map.of("rows", c[0], "firstCategoryAgrees", c[1], "expectedInSelectedSet", c[2],
            "expectedDeliveredWhole", c[3], "noCategory", c[4], "selectedButNotDelivered", c[5])));
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("measuredRevision", measuredRevision());
        inputs.put(DEV_SET_REL, sha256(devSet));
        inputs.put(FIXTURE_DOCS_REL, sha256(fixture));
        Path backendFixture = REPO_ROOT.resolve(FIXTURE_BACKEND_REL);
        if (Files.isRegularFile(backendFixture)) inputs.put(FIXTURE_BACKEND_REL, sha256(backendFixture));
        Map<String, String> guides = new TreeMap<>();
        for (String guide : consumedGuides) guides.put("docs/" + guide, sha256(REPO_DOCS.resolve(guide)));
        inputs.put("consumedGuides", guides);

        // The 150 coverage-map phrasings, reported (not gated) with the real matcher. This replaces the retired route.py
        // replica's "no category" count.
        Path phrasings = REPO_ROOT.resolve(PHRASINGS_REL);
        assertThat(phrasings).as("coverage-map phrasings are required").isRegularFile();
        int phrasingTotal = 0;
        List<String> phrasingNoCategory = new ArrayList<>();
        for (JsonNode topic : json.readTree(phrasings.toFile())) {
            int i = 0;
            for (JsonNode q : topic.get(1)) {
                i++;
                phrasingTotal++;
                if (builder.categoryIdFor(q.asText()) == null) phrasingNoCategory.add(topic.get(0).asText() + "-" + i);
            }
        }
        inputs.put(PHRASINGS_REL, sha256(phrasings));
        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("total", phrasingTotal);
        coverage.put("noCategory", phrasingNoCategory.size());
        coverage.put("noCategoryIds", phrasingNoCategory);

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("budgetChars", BUDGET);
        report.put("devSet", DEV_SET_REL + " (development, spent; never acceptance)");
        report.put("inputs", inputs);
        report.put("summaryByAudience", summary);
        report.put("coverageMapPhrasings", coverage);
        report.put("audienceLeaks", leaks.size());
        report.put("overBudget", overBudget.size());
        report.put("results", results);
        Files.createDirectories(REPORT.toAbsolutePath().getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(REPORT.toFile(), report);
        System.out.println("C1-S1 delivery summary " + summary + ", audienceLeaks=" + leaks.size() + ", overBudget=" + overBudget.size()
            + ", coverageMapNoCategory=" + phrasingNoCategory.size() + "/" + phrasingTotal + ", measuredRevision=" + inputs.get("measuredRevision"));

        assertThat(unresolved).as("expected sections that no longer resolve").isEmpty();
        assertThat(overBudget).as("contexts over the " + BUDGET + "-character budget").isEmpty();
        assertThat(leaks).as("administrator sections reaching another role").isEmpty();
        assertThat(regressions).as("baseline rows that stopped delivering their section whole").isEmpty();
        assertThat(gapNowDelivered).as("known gaps now delivered: clear their knownGap marker in the fixing change").isEmpty();
    }

    // ---- gate logic, package-private so the checks below can exercise it directly ----

    static List<Row> validateRows(JsonNode root) {
        JsonNode rows = root == null ? null : root.get("rows");
        if (rows == null || !rows.isArray() || rows.isEmpty()) throw new IllegalStateException("development set has no rows");
        List<Row> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : rows) {
            String id = text(n, "id");
            for (String field : List.of("question", "expectedCategory", "expectedSection", "audience")) text(n, field, id);
            if (!ids.add(id)) throw new IllegalStateException("duplicate row id " + id);
            String audience = n.get("audience").asText();
            if (!AUDIENCES.contains(audience)) throw new IllegalStateException("row " + id + " has unsupported audience " + audience);
            String section = n.get("expectedSection").asText();
            if (!section.matches("[^#\\s]+\\.md#[a-z0-9_-]+")) throw new IllegalStateException("row " + id + " expectedSection is not path#section: " + section);
            out.add(new Row(id, n.get("question").asText(), n.get("expectedCategory").asText(), section, audience,
                n.hasNonNull("knownGap") ? n.get("knownGap").asText() : null));
        }
        return out;
    }

    /** Audience policy declared by the development rows; two rows disagreeing about one section is itself an error. */
    static Map<String, String> audiencePolicy(List<Row> rows) {
        Map<String, String> policy = new HashMap<>();
        for (Row r : rows) {
            String prior = policy.putIfAbsent(r.expectedSection(), r.audience());
            if (prior != null && !prior.equals(r.audience())) {
                throw new IllegalStateException("rows disagree on the audience of " + r.expectedSection());
            }
        }
        return policy;
    }

    static Map<String, Set<String>> fixtureAudiences(JsonNode fixture) {
        Map<String, Set<String>> out = new HashMap<>();
        for (JsonNode category : fixture.get("categories")) {
            for (JsonNode doc : category.path("contextDocuments")) {
                out.computeIfAbsent(doc.get("path").asText() + "#" + doc.get("section").asText(), k -> new HashSet<>())
                    .add(doc.path("audience").asText("administrator"));
            }
        }
        return out;
    }

    /** A routing-map audience for a section the development set governs must match the policy exactly. */
    static List<String> contradictions(Map<String, String> policy, Map<String, Set<String>> mapAudiences) {
        List<String> out = new ArrayList<>();
        policy.forEach((section, audience) -> {
            Set<String> mapped = mapAudiences.get(section);
            if (mapped != null && !mapped.equals(Set.of(audience))) out.add(section + " policy " + audience + " map " + mapped);
        });
        return out;
    }

    static boolean deliveredWhole(KnowledgeNode node, String sourceText) {
        return sourceText != null && node.content().equals(sourceText);
    }

    static int totalChars(List<KnowledgeNode> nodes) {
        return nodes.stream().mapToInt(n -> n.content().length()).sum();
    }

    static List<String> leaksFor(String id, HouseholdRole role, List<KnowledgeNode> nodes, Set<String> adminOnly) {
        List<String> out = new ArrayList<>();
        for (KnowledgeNode node : nodes) {
            String section = sectionOf(node);
            if (adminOnly.contains(section)) out.add(id + " " + role + " " + section);
        }
        return out;
    }

    static String sectionOf(KnowledgeNode node) {
        return node.entityRef().replaceFirst("^doc:", "");
    }

    // ---- regression proof for the gates, on synthetic inputs ----

    @Test
    void aShortenedSectionWithoutTheTruncationMarkerIsNotWhole() {
        String source = "## Heading\n\nFirst paragraph.\n\nSecond paragraph.";
        assertThat(deliveredWhole(node("doc:a.md#heading", source), source)).isTrue();
        assertThat(deliveredWhole(node("doc:a.md#heading", "## Heading\n\nFirst paragraph."), source)).isFalse();
        assertThat(deliveredWhole(node("doc:a.md#heading", source), null)).as("unresolvable source").isFalse();
    }

    @Test
    void aContextOverTheBudgetIsCounted() {
        List<KnowledgeNode> nodes = List.of(node("doc:a.md#x", "x".repeat(BUDGET - 10)), node("doc:a.md#y", "y".repeat(20)));
        assertThat(totalChars(nodes)).isGreaterThan(BUDGET);
    }

    @Test
    void malformedDevelopmentSetsFailClosed() throws IOException {
        ObjectMapper json = new ObjectMapper();
        String ok = "{\"id\":\"a\",\"question\":\"q\",\"expectedCategory\":\"c\",\"expectedSection\":\"ai-assistant/x.md#s\",\"audience\":\"administrator\"}";
        assertThat(validateRows(json.readTree("{\"rows\":[" + ok + "]}"))).hasSize(1);
        assertThatThrownBy(() -> validateRows(json.readTree("{\"rows\":[]}"))).hasMessageContaining("no rows");
        assertThatThrownBy(() -> validateRows(json.readTree("{}"))).hasMessageContaining("no rows");
        assertThatThrownBy(() -> validateRows(json.readTree("{\"rows\":[" + ok + "," + ok + "]}"))).hasMessageContaining("duplicate");
        assertThatThrownBy(() -> validateRows(json.readTree("{\"rows\":[" + ok.replace("administrator", "guest") + "]}")))
            .hasMessageContaining("unsupported audience");
        assertThatThrownBy(() -> validateRows(json.readTree("{\"rows\":[" + ok.replace("\"question\":\"q\",", "") + "]}")))
            .hasMessageContaining("question");
        assertThatThrownBy(() -> validateRows(json.readTree("{\"rows\":[" + ok.replace("x.md#s", "x.md") + "]}")))
            .hasMessageContaining("path#section");
    }

    @Test
    void aRoutingMapAudienceThatContradictsThePolicyIsReported() {
        Map<String, String> policy = Map.of("ai-assistant/g.md#recovery-checks-and-notices", "administrator");
        assertThat(contradictions(policy, Map.of("ai-assistant/g.md#recovery-checks-and-notices", Set.of("administrator")))).isEmpty();
        assertThat(contradictions(policy, Map.of("ai-assistant/g.md#recovery-checks-and-notices", Set.of("household")))).hasSize(1);
        assertThat(contradictions(policy, Map.of("ai-assistant/g.md#recovery-checks-and-notices", Set.of("administrator", "household")))).hasSize(1);
        Row a = new Row("a", "q", "c", "ai-assistant/g.md#s", "administrator", null);
        Row b = new Row("b", "q", "c", "ai-assistant/g.md#s", "household", null);
        assertThatThrownBy(() -> audiencePolicy(List.of(a, b))).hasMessageContaining("disagree");
    }

    @Test
    void anAdministratorSectionReachingAnyOtherRoleIsALeak() {
        Set<String> adminOnly = Set.of("ai-assistant/g.md#secret-ish");
        List<KnowledgeNode> nodes = List.of(node("doc:ai-assistant/g.md#secret-ish", "text"), node("doc:ai-assistant/g.md#open", "text"));
        for (HouseholdRole role : OTHER_ROLES) assertThat(leaksFor("r", role, nodes, adminOnly)).as(String.valueOf(role)).hasSize(1);
        assertThat(OTHER_ROLES).contains(HouseholdRole.SERVICE).containsNull();
    }

    // ---- helpers ----

    private static KnowledgeNode node(String ref, String content) {
        return new KnowledgeNode(ref, com.cabin.orchestrator.devices.model.KnowledgeChunkType.DESCRIPTION, content,
            com.cabin.orchestrator.devices.model.KnowledgeSource.REVIEWED_DOCUMENT, null);
    }

    private static String text(JsonNode n, String field) {
        return text(n, field, "?");
    }

    private static String text(JsonNode n, String field, String id) {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw new IllegalStateException("row " + id + " is missing " + field);
        return v.asText();
    }

    private static String measuredRevision() {
        String env = System.getenv("GITHUB_SHA");
        if (env != null && !env.isBlank()) return env;
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "HEAD").directory(REPO_ROOT.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return p.waitFor() == 0 && out.matches("[0-9a-f]{40}") ? out : "unknown";
        } catch (IOException | InterruptedException e) {
            return "unknown";
        }
    }

    private static String sha256(Path file) throws IOException {
        try {
            byte[] bytes = Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
