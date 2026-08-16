package com.cabin.orchestrator.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-15: setDone() replaces the old toggle(), which read-then-flipped
 * — not idempotent, so two devices acting near-simultaneously (or a
 * retried request after a dropped response) could double-flip a chore
 * back to the state it started in even though the user's real intent was
 * simply "mark it done." These tests are specifically about that
 * property: repeating the same setDone() call must be a no-op, not a
 * second flip.
 */
@Testcontainers
class ChoreCompletionServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private ChoreCompletionService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS chore_completion");
        service = new ChoreCompletionService(jdbc);
    }

    @Test
    void setDoneTrueThenTrueAgainStaysDoneInsteadOfFlippingBack() {
        service.setDone("2026-08-15", "sam", "make_bed", true);
        service.setDone("2026-08-15", "sam", "make_bed", true); // retry / second device, same intent

        Map<String, Boolean> row = service.all().get("2026-08-15_sam");
        assertThat(row).containsEntry("make_bed", true);
    }

    @Test
    void setDoneFalseAfterTrueClearsIt() {
        service.setDone("2026-08-15", "sam", "make_bed", true);
        service.setDone("2026-08-15", "sam", "make_bed", false);

        Map<String, Boolean> row = service.all().get("2026-08-15_sam");
        assertThat(row == null || !row.containsKey("make_bed")).isTrue();
    }

    @Test
    void allOnlyReturnsRowsMarkedDone() {
        service.setDone("2026-08-15", "sam", "make_bed", true);
        service.setDone("2026-08-15", "sam", "reading", false);

        Map<String, Boolean> row = service.all().get("2026-08-15_sam");
        assertThat(row).containsKey("make_bed");
        assertThat(row).doesNotContainKey("reading");
    }

    @Test
    void completionIsKeyedIndependentlyPerChildAndDay() {
        service.setDone("2026-08-15", "sam", "make_bed", true);
        service.setDone("2026-08-15", "emma", "make_bed", true);
        service.setDone("2026-08-14", "sam", "make_bed", true);

        assertThat(service.all()).containsKeys("2026-08-15_sam", "2026-08-15_emma", "2026-08-14_sam");
    }
}
