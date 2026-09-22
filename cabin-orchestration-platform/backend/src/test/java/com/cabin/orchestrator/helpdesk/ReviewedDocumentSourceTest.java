package com.cabin.orchestrator.helpdesk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewedDocumentSourceTest {

    private static final String GUIDE = "ai-assistant/user-guide/sample.md";

    @TempDir
    Path docsRoot;

    private ReviewedDocumentSource source() {
        return new ReviewedDocumentSource(docsRoot.toString());
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = docsRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void slug_followsGitHubAnchorRules() {
        assertThat(ReviewedDocumentSource.slug("Users and family data")).isEqualTo("users-and-family-data");
        assertThat(ReviewedDocumentSource.slug("Persistence and replacement-host recovery"))
            .isEqualTo("persistence-and-replacement-host-recovery");
        assertThat(ReviewedDocumentSource.slug("Rules & safety (v2)")).isEqualTo("rules--safety-v2");
        assertThat(ReviewedDocumentSource.slug("The `HA_TOKEN` [guard](x.md)")).isEqualTo("the-ha_token-guard");
    }

    @Test
    void section_includesItsSubsectionsButStopsAtTheNextSiblingHeading() throws IOException {
        write(GUIDE, """
            # Title

            ## First
            first body

            ### Nested
            nested body

            ## Second
            second body
            """);

        Optional<ReviewedDocumentSource.DocumentSection> section = source().section(GUIDE, "first");

        assertThat(section).isPresent();
        assertThat(section.get().text()).startsWith("## First")
            .contains("first body", "### Nested", "nested body")
            .doesNotContain("second body");
        assertThat(section.get().heading()).isEqualTo("## First");
    }

    @Test
    void headingsInsideCodeFencesAreNotSections() throws IOException {
        write(GUIDE, """
            ## Real
            before
            ```bash
            # a shell comment, not a heading
            echo hi
            ```
            after
            """);

        ReviewedDocumentSource source = source();

        assertThat(source.section(GUIDE, "real").orElseThrow().text()).contains("echo hi", "after");
        assertThat(source.section(GUIDE, "a-shell-comment-not-a-heading")).isEmpty();
    }

    @Test
    void repeatedHeadingsGetNumberedAnchors() throws IOException {
        write(GUIDE, """
            ## Notes
            one
            ## Notes
            two
            """);

        ReviewedDocumentSource source = source();

        assertThat(source.section(GUIDE, "notes").orElseThrow().text()).contains("one").doesNotContain("two");
        assertThat(source.section(GUIDE, "notes-1").orElseThrow().text()).contains("two");
    }

    @Test
    void missingSectionOrFile_isEmptyNotAnError() throws IOException {
        write(GUIDE, "## Only\nbody\n");

        assertThat(source().section(GUIDE, "absent")).isEmpty();
        assertThat(source().section("ai-assistant/user-guide/missing.md", "only")).isEmpty();
    }

    @Test
    void onlyAllowlistedPathsAreReadable() throws IOException {
        write("ai-assistant/corpus/eval-answer-evidence.md", "## Q02\nthe golden answer\n");
        write("ai-assistant/user-guide/ok.md", "## Fine\nbody\n");
        write("ai-assistant/contributing.md", "## Shared\nbody\n");
        write("ai-assistant/user-guide/notes.txt", "## Fine\nbody\n");
        write("secret.md", "## S\nbody\n");

        ReviewedDocumentSource source = source();

        assertThat(source.section("ai-assistant/user-guide/ok.md", "fine")).isPresent();
        assertThat(source.section("ai-assistant/contributing.md", "shared")).isPresent();
        // The eval oracle holds the expected answers to the frozen questions -- never source data.
        assertThat(source.section("ai-assistant/corpus/eval-answer-evidence.md", "q02")).isEmpty();
        assertThat(source.section("ai-assistant/user-guide/notes.txt", "fine")).isEmpty();
        assertThat(source.section("secret.md", "s")).isEmpty();
    }

    @Test
    void traversalAndAbsolutePathsAreRefused() {
        assertThat(ReviewedDocumentSource.isAllowed("ai-assistant/user-guide/../../secret.md")).isFalse();
        assertThat(ReviewedDocumentSource.isAllowed("/etc/passwd.md")).isFalse();
        assertThat(ReviewedDocumentSource.isAllowed("ai-assistant\\user-guide\\ok.md")).isFalse();
        assertThat(ReviewedDocumentSource.isAllowed("")).isFalse();
        assertThat(ReviewedDocumentSource.isAllowed(null)).isFalse();
        assertThat(ReviewedDocumentSource.isAllowed("ai-assistant/user-guide/ok.md")).isTrue();
    }

    @Test
    void anEditedFileIsReReadWithoutARestart() throws IOException {
        write(GUIDE, "## Live\nold text\n");
        ReviewedDocumentSource source = source();
        assertThat(source.section(GUIDE, "live").orElseThrow().text()).contains("old text");

        Path file = docsRoot.resolve(GUIDE);
        Files.writeString(file, "## Live\nnew text\n");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(60)));

        assertThat(source.section(GUIDE, "live").orElseThrow().text()).contains("new text").doesNotContain("old text");
    }

    @Test
    void aMissingDocsRoot_disablesDocumentsWithoutFailingStartup() {
        ReviewedDocumentSource source = new ReviewedDocumentSource(docsRoot.resolve("not-mounted").toString());

        assertThat(source.section(GUIDE, "anything")).isEmpty();
    }
}
