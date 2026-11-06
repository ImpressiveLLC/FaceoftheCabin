package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The word-overlap supplement to the context map: a question no map keyword anticipates still reaches the
 * guide section that shares its words, under the same role gate, and the supplement can be switched off.
 */
class AskLexicalSectionTest {

    private static final String GUIDE = "ai-assistant/user-guide/sample.md";

    @TempDir
    Path docsRoot;

    private Path fixtures;

    @BeforeEach
    void setUp() throws IOException {
        Path guide = docsRoot.resolve(GUIDE);
        Files.createDirectories(guide.getParent());
        Files.writeString(guide, """
            # Sample guide

            ## Garden irrigation

            ### Reset the sprinkler timer

            Hold the sprinkler timer button for ten seconds to reset the sprinkler timer after a power cut.

            ### Change the watering days

            Open the schedule screen and pick the watering days for each zone.

            ## Mapped topic

            ### Door chime volume

            The door chime volume is set from the chime settings page.
            """);
        fixtures = docsRoot.resolve("fixtures.json");
        Files.writeString(fixtures, """
            {"categories": [
              {"id": "chime", "keywordPatterns": ["chime"], "contextNodes": [],
               "contextDocuments": [{"path": "ai-assistant/user-guide/sample.md", "section": "door-chime-volume",
                                     "audience": "household"}]}
            ]}
            """);
    }

    private AskContextBuilder builder(int lexicalSections, int minTerms) {
        return new AskContextBuilder(new NoNodes(), new ReviewedDocumentSource(docsRoot.toString()),
            new DefaultResourceLoader(), fixtures.toUri().toString(), 6000, lexicalSections, minTerms);
    }

    private static List<String> refs(List<KnowledgeNode> context) {
        return context.stream().map(KnowledgeNode::entityRef).toList();
    }

    @Test
    void leafSections_skipParentsSoTheirTextIsNotCountedTwice() {
        List<String> anchors = new ReviewedDocumentSource(docsRoot.toString()).leafSections().stream()
            .map(ReviewedDocumentSource.DocumentSection::anchor).toList();

        assertThat(anchors).containsExactly("change-the-watering-days", "door-chime-volume",
            "reset-the-sprinkler-timer");
    }

    @Test
    void unmappedQuestion_reachesTheSectionThatSharesItsWords_forAnAdministrator() {
        List<KnowledgeNode> context = builder(3, 3).buildContext(
            "How do I reset the sprinkler timer after the power cut?", HouseholdRole.ADMINISTRATOR);

        assertThat(refs(context)).containsExactly("doc:" + GUIDE + "#reset-the-sprinkler-timer");
    }

    @Test
    void unmappedSectionIsAdministratorOnly_failClosed() {
        AskContextBuilder builder = builder(3, 3);

        assertThat(builder.buildContext("How do I reset the sprinkler timer after the power cut?", null)).isEmpty();
        assertThat(builder.buildContext("How do I reset the sprinkler timer after the power cut?",
            HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).isEmpty();
    }

    @Test
    void sectionTheMapListsAsHousehold_staysReachableToEveryRole() {
        List<KnowledgeNode> context = builder(3, 2).buildContext(
            "Where is the door chime volume set?", HouseholdRole.ADULT_HOUSEHOLD_MEMBER);

        assertThat(refs(context)).containsExactly("doc:" + GUIDE + "#door-chime-volume");
    }

    @Test
    void switchedOff_behavesAsBeforeAndSuppliesNothingForAnUnmappedQuestion() {
        AskContextBuilder off = new AskContextBuilder(new NoNodes(), new ReviewedDocumentSource(docsRoot.toString()),
            new DefaultResourceLoader(), fixtures.toUri().toString(), 6000);

        assertThat(off.buildContext("How do I reset the sprinkler timer after the power cut?",
            HouseholdRole.ADMINISTRATOR)).isEmpty();
        assertThat(builder(0, 3).buildContext("How do I reset the sprinkler timer after the power cut?",
            HouseholdRole.ADMINISTRATOR)).isEmpty();
    }

    @Test
    void tooFewSharedWords_addsNothing() {
        assertThat(builder(3, 3).buildContext("Is the sprinkler broken?", HouseholdRole.ADMINISTRATOR)).isEmpty();
    }

    @Test
    void lexicalSectionComesFirstAndIsNotRepeatedByTheCategory() {
        List<KnowledgeNode> context = builder(3, 1).buildContext(
            "chime volume", HouseholdRole.ADMINISTRATOR);

        assertThat(refs(context)).containsExactly("doc:" + GUIDE + "#door-chime-volume");
    }

    @Test
    void ranker_prefersHeadingMatchesAndIsDeterministic() {
        ReviewedDocumentSource source = new ReviewedDocumentSource(docsRoot.toString());
        List<ReviewedDocumentSource.DocumentSection> candidates = source.leafSections();

        List<GuideSectionRanker.Ranked> first = GuideSectionRanker.rank("sprinkler timer reset", candidates, 3, 1);
        List<GuideSectionRanker.Ranked> second = GuideSectionRanker.rank("sprinkler timer reset", candidates, 3, 1);

        assertThat(first.get(0).section().anchor()).isEqualTo("reset-the-sprinkler-timer");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void ranker_appliesTheTermGateAfterTheCut_soALowerSectionNeverTakesATopSlot() {
        List<ReviewedDocumentSource.DocumentSection> candidates =
            new ReviewedDocumentSource(docsRoot.toString()).leafSections();

        // limit 1: the best section shares only two words, so the gate of three leaves nothing,
        // even though another section might share three common words.
        assertThat(GuideSectionRanker.rank("sprinkler timer volume settings page", candidates, 1, 3)).isEmpty();
    }

    @Test
    void ranker_returnsNothingForBlankOrStopWordOnlyQuestions() {
        List<ReviewedDocumentSource.DocumentSection> candidates =
            new ReviewedDocumentSource(docsRoot.toString()).leafSections();

        assertThat(GuideSectionRanker.rank("", candidates, 3, 1)).isEmpty();
        assertThat(GuideSectionRanker.rank("how do I", candidates, 3, 1)).isEmpty();
        assertThat(GuideSectionRanker.rank("sprinkler", List.of(), 3, 1)).isEmpty();
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
