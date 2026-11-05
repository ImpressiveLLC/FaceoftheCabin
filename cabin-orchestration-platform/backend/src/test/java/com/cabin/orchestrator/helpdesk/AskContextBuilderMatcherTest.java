package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Matcher behavior on invented words (rag/matcher-synthetic.json): keyword endings, and what happens when
 * categories tie. Checks the class of failure, not any question: no evaluation wording appears here.
 */
class AskContextBuilderMatcherTest {

    private static final String FIXTURES = "classpath:rag/matcher-synthetic.json";
    private static final String GUIDE = "doc:ai-assistant/user-guide/syn.md#";

    @TempDir
    Path docsRoot;

    private AskContextBuilder builder;

    @BeforeEach
    void setUp() throws IOException {
        Path guide = docsRoot.resolve("ai-assistant/user-guide");
        Files.createDirectories(guide);
        Files.writeString(guide.resolve("syn.md"),
            "# Synthetic guide\n\n## Alpha part\n\n" + "a".repeat(700) + "\n\n"
                + "## Beta part\n\n" + "b".repeat(700) + "\n\n"
                + "## Small part\n\n" + "s".repeat(200) + "\n");
        builder = builder(1000);
    }

    private AskContextBuilder builder(int budget) {
        List<KnowledgeNode> nodes = new ArrayList<>();
        for (String ref : List.of("syn.alpha", "syn.beta", "syn.gamma", "syn.delta", "syn.shared", "syn.stem", "syn.dup", "syn.one")) {
            nodes.add(new KnowledgeNode(ref, KnowledgeChunkType.DESCRIPTION, ref + " text",
                KnowledgeSource.AUTO_GENERATED, Instant.now()));
        }
        KnowledgeNodeRepository repo = new KnowledgeNodeRepository() {
            @Override public void upsert(KnowledgeNode node) { nodes.add(node); }
            @Override public List<KnowledgeNode> findByEntityRef(String ref) {
                return nodes.stream().filter(n -> n.entityRef().equals(ref)).toList();
            }
            @Override public List<KnowledgeNode> loadAll() { return List.copyOf(nodes); }
        };
        return new AskContextBuilder(repo, new ReviewedDocumentSource(docsRoot.toString()),
            new DefaultResourceLoader(), FIXTURES, budget);
    }

    private static List<String> refs(List<KnowledgeNode> context) {
        return context.stream().map(KnowledgeNode::entityRef).toList();
    }

    private static List<KnowledgeNode> documents(List<KnowledgeNode> context) {
        return context.stream().filter(n -> n.source() == KnowledgeSource.REVIEWED_DOCUMENT).toList();
    }

    // ---- keyword endings ------------------------------------------------------------------------

    @Test
    void aKeywordMatchesItsInflectedForms() {
        // keyword without a final e
        for (String word : List.of("gronk", "gronks", "gronked", "gronking", "gronkings", "gronkly",
                "gronker", "gronkers", "gronkation", "gronkations")) {
            assertThat(builder.categoryIdFor("does the " + word + " work")).as(word).isEqualTo("cat_stem");
        }
        // keyword with a final e also takes the e-dropping forms
        for (String word : List.of("wibble", "wibbles", "wibbled", "wibbling", "wibblings", "wibblation",
                "wibblations", "frobnicate", "frobnicating", "frobnicated")) {
            assertThat(builder.categoryIdFor("does the " + word + " work")).as(word).isEqualTo("cat_stem");
        }
    }

    @Test
    void endingsStillRequireAWholeWord() {
        assertThat(builder.categoryIdFor("a rewibble step")).isNull();   // prefix: not the keyword
        assertThat(builder.categoryIdFor("a wibbleish step")).isNull();  // ending not in the list
        assertThat(builder.categoryIdFor("a wibble_x step")).isNull();   // identifier character follows
    }

    @Test
    void twoKeywordsThatDifferOnlyByAnEndingScoreTheSameWordOnce() {
        // cat_dup lists glorp and glorped; both match the word "glorped" once endings are accepted, but the
        // word is worth one keyword. cat_one lists only glorped, so the two tie instead of cat_dup winning 2 to 1.
        assertThat(builder.categoryIdsFor("is glorped on")).containsExactly("cat_dup", "cat_one");
    }

    // ---- ties -----------------------------------------------------------------------------------

    @Test
    void categoriesTiedForTheTopScoreAreAllKept_inDeclarationOrder() {
        assertThat(builder.categoryIdsFor("tell me about zorbit")).containsExactly("cat_alpha", "cat_beta", "cat_gamma");
        assertThat(builder.categoryIdFor("tell me about zorbit")).isEqualTo("cat_alpha");

        List<String> nodes = refs(builder.buildContext("tell me about zorbit"));
        assertThat(nodes).contains("syn.alpha", "syn.beta", "syn.gamma");
        assertThat(nodes.indexOf("syn.alpha")).isLessThan(nodes.indexOf("syn.beta"));
    }

    @Test
    void aHigherScoreStillWinsAlone() {
        assertThat(builder.categoryIdsFor("zorbit and flumph")).containsExactly("cat_gamma");
        List<String> context = refs(builder.buildContext("zorbit and flumph"));
        assertThat(context).contains("syn.gamma").doesNotContain("syn.alpha", "syn.beta", "syn.delta");
    }

    @Test
    void aNodeSharedByTiedCategoriesIsSuppliedOnce() {
        List<String> nodes = refs(builder.buildContext("zorbit"));
        assertThat(nodes.stream().filter("syn.shared"::equals)).hasSize(1);
    }

    @Test
    void noMatchSelectsNothing() {
        assertThat(builder.categoryIdsFor("nothing here")).isEmpty();
        assertThat(builder.buildContext("nothing here")).isEmpty();
    }

    // ---- ties and the document budget -----------------------------------------------------------

    @Test
    void aLaterTiedCategoryAddsOnlySectionsThatFitWhole_andNeverRepeatsOne() {
        // Budget 1000: alpha-part (about 715) goes first. beta repeats alpha-part (dropped) and its own
        // beta-part (about 715) does not fit whole in the ~285 left, so it is skipped, not truncated.
        // gamma's small-part (about 215) fits.
        List<KnowledgeNode> docs = documents(builder.buildContext("zorbit", null));

        assertThat(docs.stream().map(KnowledgeNode::entityRef)).containsExactly(
            GUIDE + "alpha-part", GUIDE + "small-part");
        assertThat(docs).noneMatch(d -> d.content().contains("[section truncated"));
        assertThat(docs.stream().mapToInt(d -> d.content().length()).sum()).isLessThanOrEqualTo(1000);
    }

    @Test
    void theFirstCategoryOfATieStillTruncatesToTheBudget() {
        List<KnowledgeNode> docs = documents(builder(500).buildContext("zorbit", null));

        assertThat(docs).isNotEmpty();
        assertThat(docs.get(0).entityRef()).isEqualTo(GUIDE + "alpha-part");
        assertThat(docs.get(0).content()).contains("[section truncated");
    }
}
