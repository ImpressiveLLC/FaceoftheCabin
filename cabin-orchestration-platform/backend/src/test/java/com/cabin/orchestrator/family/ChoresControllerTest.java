package com.cabin.orchestrator.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 2026-08-15: the new PUT /completions/{date}/{childId}/{assignmentId}
 * endpoint has real branching logic (resolve assignment -> chore id,
 * reject a mismatched child, reject a missing/non-boolean done) that
 * ChoreCompletionServiceTest alone doesn't exercise -- that class tests
 * setDone() directly with an already-known choreId, not the
 * assignment-resolution this controller does first.
 */
@Testcontainers
class ChoresControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private ChoresController controller;
    private ChoreCompletionService completion;
    private ChoreAssignmentService assignments;
    private String assignmentId;
    private final String today = LocalDate.now().toString();

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS chore_completion");
        jdbc.execute("DROP TABLE IF EXISTS chore_assignment");
        jdbc.execute("DROP TABLE IF EXISTS family_profiles");
        FamilyProfileService profiles = new FamilyProfileService(jdbc);
        assignments = new ChoreAssignmentService(jdbc, profiles);
        completion = new ChoreCompletionService(jdbc);
        controller = new ChoresController(completion, assignments);
        assignmentId = assignments.list("sam").get(0).id();
    }

    @Test
    void setCompletionResolvesTheAssignmentToItsChoreIdAndPersists() {
        String choreId = assignments.byId(assignmentId).choreDefinitionId();

        Map<String, Object> result = controller.setCompletion(today, "sam", assignmentId, Map.of("done", true));

        assertThat(result.get("choreId")).isEqualTo(choreId);
        assertThat(completion.all().get(today + "_sam")).containsEntry(choreId, true);
    }

    @Test
    void repeatingTheSameSetCompletionCallStaysIdempotent() {
        controller.setCompletion(today, "sam", assignmentId, Map.of("done", true));
        controller.setCompletion(today, "sam", assignmentId, Map.of("done", true));

        String choreId = assignments.byId(assignmentId).choreDefinitionId();
        assertThat(completion.all().get(today + "_sam")).containsEntry(choreId, true);
    }

    @Test
    void rejectsAnAssignmentThatBelongsToADifferentChild() {
        assertThatThrownBy(() -> controller.setCompletion(today, "emma", assignmentId, Map.of("done", true)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAnUnknownAssignmentId() {
        assertThatThrownBy(() -> controller.setCompletion(today, "sam", "does-not-exist", Map.of("done", true)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingDoneField() {
        assertThatThrownBy(() -> controller.setCompletion(today, "sam", assignmentId, Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
