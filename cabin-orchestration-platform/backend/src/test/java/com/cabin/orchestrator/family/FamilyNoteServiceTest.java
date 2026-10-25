package com.cabin.orchestrator.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class FamilyNoteServiceTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private JdbcTemplate jdbc;
    private FamilyNoteService service;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(new org.postgresql.Driver(),
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS family_notes");
        service = new FamilyNoteService(jdbc);
        service.init();
    }

    @Test void upgradesOldTableWithoutLosingNotesAndCanRunTwice() {
        jdbc.execute("DROP TABLE family_notes");
        jdbc.execute("CREATE TABLE family_notes (id VARCHAR(64) PRIMARY KEY, author_id VARCHAR(128) NOT NULL, text TEXT NOT NULL, ts BIGINT NOT NULL)");
        jdbc.update("INSERT INTO family_notes VALUES ('old','sam','Keep me',1)");
        service.init(); service.init();
        assertThat(service.recent()).singleElement().satisfies(note -> {
            assertThat(note.text()).isEqualTo("Keep me");
            assertThat(note.kind()).isEqualTo("NOTE");
            assertThat(note.replyToId()).isNull();
        });
    }

    @Test void stickyListAndBothDateModesSurviveReload() {
        service.add("sam", "Milk\nEggs", "STICKY", "DUE_BY", "2026-11-02", null);
        service.add("emma", "Dinner: pasta", "STICKY", "LIST_DATE", "2026-11-03", null);
        assertThat(new FamilyNoteService(jdbc).recent()).hasSize(2).allSatisfy(note -> {
            assertThat(note.kind()).isEqualTo("STICKY");
            assertThat(note.date()).startsWith("2026-11-");
        });
    }

    @Test void repliesKeepServerCapturedContextEvenAfterOriginalIsPruned() {
        var parent = service.add("sam", "What about dinner?");
        var reply = service.add("emma", "Pasta", "NOTE", null, null, parent.id());
        jdbc.update("DELETE FROM family_notes WHERE id = ?", parent.id());
        assertThat(service.recent()).singleElement().satisfies(note -> {
            assertThat(note.id()).isEqualTo(reply.id());
            assertThat(note.replyText()).isEqualTo("What about dinner?");
            assertThat(note.replyAuthorId()).isEqualTo("sam");
        });
    }

    @Test void rejectsMissingParentAndInvalidMetadataWithoutWriting() {
        assertThatThrownBy(() -> service.add("sam", "Reply", "NOTE", null, null, "missing"))
            .isInstanceOf(ResponseStatusException.class);
        for (String date : new String[]{null, "2026-02-30", "tomorrow"}) {
            assertThatThrownBy(() -> service.add("sam", "List", "STICKY", "DUE_BY", date, null))
                .isInstanceOf(ResponseStatusException.class);
        }
        assertThatThrownBy(() -> service.add("sam", "List", "STICKY", "OTHER", "2026-01-01", null))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.add("sam", "Note", "NOTE", "DUE_BY", "2026-01-01", null))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> service.add("sam", "x".repeat(2001)))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(service.recent()).isEmpty();
    }
}
