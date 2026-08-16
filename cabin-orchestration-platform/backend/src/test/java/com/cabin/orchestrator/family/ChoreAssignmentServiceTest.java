package com.cabin.orchestrator.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-15: the architectural correction this whole feature exists for
 * is "stop deriving assignments from age and calendar arithmetic in the
 * browser" -- these tests cover both halves of that: the one-time seed
 * migration (which still has to reproduce the OLD algorithm once, so nothing
 * visibly changes at migration time) and applicableOn()'s new date/
 * recurrence-based filtering, which is what replaces it going forward.
 */
@Testcontainers
class ChoreAssignmentServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private ChoreAssignmentService service;
    private String todayKey;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS chore_assignment");
        jdbc.execute("DROP TABLE IF EXISTS family_profiles");
        FamilyProfileService profiles = new FamilyProfileService(jdbc); // seeds sam(9)/emma(6)/frankie/nathan
        service = new ChoreAssignmentService(jdbc, profiles); // seeds assignments from those kids' current ages
        todayKey = LocalDate.now().toString();
    }

    @Test
    void seedsThreeDailyPlusOneRotatingChorePerExistingKid() {
        List<ChoreAssignment> samAssignments = service.list("sam");
        List<ChoreAssignment> emmaAssignments = service.list("emma");

        assertThat(samAssignments).hasSize(4);
        assertThat(emmaAssignments).hasSize(4);
        assertThat(samAssignments).extracting(ChoreAssignment::choreDefinitionId)
            .contains("brush_teeth", "make_bed", "reading");
        assertThat(samAssignments).allMatch(a -> "DAILY".equals(a.recurrence()) && a.effectiveEnd() == null);
    }

    @Test
    void seededRotatingChoreMatchesTheOldAlgorithmsTodaysPick() {
        int dayOfMonth = LocalDate.now().getDayOfMonth();
        List<String> samRotating = List.of("clear_table", "unload_dishwasher", "take_trash", "walk_frankie", "homework");
        List<String> emmaRotating = List.of("set_table", "wipe_table", "outfit_ready", "lunch_backpack", "put_away_laundry");
        String expectedSamPick = samRotating.get(dayOfMonth % samRotating.size());
        String expectedEmmaPick = emmaRotating.get(dayOfMonth % emmaRotating.size());

        assertThat(service.list("sam")).extracting(ChoreAssignment::choreDefinitionId).contains(expectedSamPick);
        assertThat(service.list("emma")).extracting(ChoreAssignment::choreDefinitionId).contains(expectedEmmaPick);
    }

    @Test
    void seedingIsSkippedOnceTheTableIsNoLongerEmpty() {
        int before = service.list(null).size();
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        new ChoreAssignmentService(jdbc, new FamilyProfileService(jdbc));
        assertThat(service.list(null)).hasSize(before);
    }

    @Test
    void applicableOnExcludesADailyAssignmentBeforeItsEffectiveStart() {
        ChoreAssignment created = service.create(new ChoreAssignment(
            null, "vacuum_room", "sam", true, "DAILY", todayKey, null, 0, null, 0, 0, null, null), "sam");

        String yesterday = LocalDate.now().minusDays(1).toString();
        assertThat(service.applicableOn("sam", todayKey)).extracting(ChoreAssignment::id).contains(created.id());
        assertThat(service.applicableOn("sam", yesterday)).extracting(ChoreAssignment::id).doesNotContain(created.id());
    }

    @Test
    void applicableOnHandlesOneDayAssignmentsExactly() {
        String tomorrow = LocalDate.now().plusDays(1).toString();
        ChoreAssignment created = service.create(new ChoreAssignment(
            null, "vacuum_room", "sam", true, "ONE_DAY", tomorrow, null, 0, null, 0, 0, null, null), "sam");

        assertThat(created.effectiveEnd()).isEqualTo(tomorrow); // ONE_DAY forces end == start regardless of what was passed
        assertThat(service.applicableOn("sam", tomorrow)).extracting(ChoreAssignment::id).contains(created.id());
        assertThat(service.applicableOn("sam", todayKey)).extracting(ChoreAssignment::id).doesNotContain(created.id());
    }

    @Test
    void applicableOnRespectsAnExplicitEffectiveEnd() {
        String end = LocalDate.now().plusDays(2).toString();
        String afterEnd = LocalDate.now().plusDays(3).toString();
        ChoreAssignment created = service.create(new ChoreAssignment(
            null, "vacuum_room", "sam", true, "DAILY", todayKey, end, 0, null, 0, 0, null, null), "sam");

        assertThat(service.applicableOn("sam", end)).extracting(ChoreAssignment::id).contains(created.id());
        assertThat(service.applicableOn("sam", afterEnd)).extracting(ChoreAssignment::id).doesNotContain(created.id());
    }

    @Test
    void updateReassignsAnAssignmentToADifferentChild() {
        ChoreAssignment created = service.create(new ChoreAssignment(
            null, "vacuum_room", "sam", true, "DAILY", todayKey, null, 0, null, 0, 0, null, null), "sam");

        ChoreAssignment reassigned = service.update(created.id(), new ChoreAssignment(
            created.id(), null, "emma", true, null, null, null, 0, null, 0, 0, null, null), "sam");

        assertThat(reassigned.childId()).isEqualTo("emma");
        assertThat(service.list("sam")).extracting(ChoreAssignment::id).doesNotContain(created.id());
        assertThat(service.list("emma")).extracting(ChoreAssignment::id).contains(created.id());
    }

    @Test
    void updateCanClearEffectiveEndBackToOngoingViaEmptyStringSentinel() {
        String end = LocalDate.now().plusDays(2).toString();
        ChoreAssignment created = service.create(new ChoreAssignment(
            null, "vacuum_room", "sam", true, "DAILY", todayKey, end, 0, null, 0, 0, null, null), "sam");
        assertThat(created.effectiveEnd()).isEqualTo(end);

        ChoreAssignment cleared = service.update(created.id(), new ChoreAssignment(
            created.id(), null, null, true, null, null, "", 0, null, 0, 0, null, null), "sam");

        assertThat(cleared.effectiveEnd()).isNull();
    }

    @Test
    void updateLeavesEffectiveEndUntouchedWhenNotProvided() {
        String end = LocalDate.now().plusDays(2).toString();
        ChoreAssignment created = service.create(new ChoreAssignment(
            null, "vacuum_room", "sam", true, "DAILY", todayKey, end, 0, null, 0, 0, null, null), "sam");

        ChoreAssignment updated = service.update(created.id(), new ChoreAssignment(
            created.id(), "homework", null, true, null, null, null, 0, null, 0, 0, null, null), "sam");

        assertThat(updated.choreDefinitionId()).isEqualTo("homework");
        assertThat(updated.effectiveEnd()).isEqualTo(end);
    }

    @Test
    void removeDeletesTheAssignment() {
        ChoreAssignment created = service.create(new ChoreAssignment(
            null, "vacuum_room", "sam", true, "DAILY", todayKey, null, 0, null, 0, 0, null, null), "sam");

        boolean removed = service.remove(created.id());

        assertThat(removed).isTrue();
        assertThat(service.byId(created.id())).isNull();
    }

    @Test
    void reorderOnlyAffectsTheGivenChild() {
        List<ChoreAssignment> samBefore = service.list("sam");
        service.reorder("sam", List.of(samBefore.get(1).id(), samBefore.get(0).id()), "sam");

        List<ChoreAssignment> samAfter = service.list("sam");
        assertThat(samAfter.get(0).id()).isEqualTo(samBefore.get(1).id());
        assertThat(samAfter.get(1).id()).isEqualTo(samBefore.get(0).id());
    }
}
