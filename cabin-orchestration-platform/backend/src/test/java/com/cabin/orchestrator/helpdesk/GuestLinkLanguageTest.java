package com.cabin.orchestrator.helpdesk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W-139 (Nate, 2026-11-01): a guest link is a convenience view of data that is open without signing in (D14), made for sharing a look.
 * The wording is pinned in each repo document that describes it, so a later edit cannot drift back to promising a lock around the data.
 * These pins live here, not in the UI tests, because the UI test image (ui/test/Dockerfile) holds none of these files; the pins for
 * App.jsx are in ui/src/GuestLinkLanguage.test.jsx.
 */
class GuestLinkLanguageTest {

    private static final Path REPO = Path.of("../..").toAbsolutePath().normalize();
    private static final Path GUIDE = REPO.resolve("docs/ai-assistant/user-guide/access-and-storage.md");

    private static String read(Path path) throws IOException {
        return Files.readString(path).replace("\r\n", "\n");
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String between(String text, String from, String to) {
        int a = text.indexOf(from);
        int b = text.indexOf(to);
        assertThat(a).as(from).isGreaterThanOrEqualTo(0);
        assertThat(b).as(to).isGreaterThan(a);
        return text.substring(a, b);
    }

    @Test
    void theUserGuideSaysWhatAGuestLinkIsInTheTableRowAndTheGuestAccessSection() throws IOException {
        assumeTrue(Files.isRegularFile(GUIDE), "repo docs/ not reachable");
        String guide = read(GUIDE);
        assertThat(guide).contains("A convenience view for sharing a look: read-only");
        assertThat(guide).contains("A guest link is a convenience view of data that is open without signing in (D14)");
        assertThat(guide).doesNotContainPattern("cannot open anything you did not tick|reaches nothing else");
        assertThat(guide).contains("**Revoke** marks a link Revoked");
    }

    @Test
    void theGuestAccessSectionSaysNothingAboutEffectOfRevokeLimitingTicksOrUsageOnGuestLinks() throws IOException {
        assumeTrue(Files.isRegularFile(GUIDE), "repo docs/ not reachable");
        String guide = read(GUIDE);
        String guestSection = between(guide, "## Share read-only access with a link", "## Give someone a demo link");
        assertThat(guestSection.toLowerCase()).doesNotContain("stops it at once").doesNotContain("choose the parts to include")
            .doesNotContain("last opened").doesNotContain("view activity");
        // demo links still show Last opened and View activity, so the demo section explains them
        String demoSection = between(guide, "## Give someone a demo link", "## Add someone without a Google account");
        assertThat(demoSection).containsPattern("\\*\\*Last opened\\*\\*[^\\n]*\\*\\*View activity\\*\\*");
    }

    @Test
    void theOntologyHasNoClaimAboutFixedScopePrefixesAndBothInstructionFilesUseTheSameWording() throws IOException {
        Path ontology = REPO.resolve("docs/ontology.yaml");
        assumeTrue(Files.isRegularFile(ontology), "repo docs/ not reachable");
        // whitespace collapsed first, so a re-wrapped copy of the old claim still fails
        assertThat(collapse(read(ontology))).doesNotContain("four fixed read-only scope prefixes");
        for (String file : new String[] {"CLAUDE.md", "AGENTS.md"}) {
            Path path = REPO.resolve(file);
            assumeTrue(Files.isRegularFile(path), file + " not reachable");
            assertThat(read(path)).as(file).contains("a guest link is a convenience view of data that is open without signing in, D14");
        }
    }
}
