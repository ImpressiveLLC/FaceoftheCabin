package com.cabin.orchestrator.family;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-20: the whole point of this migration is that the family's real,
 * already-live custody schedule (the two DEFAULT_SCHEDULE_RULES versions
 * hardcoded in family-hub.html) survives the move to a backend unchanged --
 * these tests pin the seeded ids/dayOwners to that exact data, and pin the
 * amend-vs-append versioning rule that keeps historical days resolving
 * correctly once a new schedule version starts.
 */
@Testcontainers
class ScheduleRuleServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private ScheduleRuleService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = jdbcOf();
        jdbc.execute("DROP TABLE IF EXISTS schedule_rule");
        service = new ScheduleRuleService(jdbc, new ObjectMapper()); // constructor seeds on an empty table
    }

    private JdbcTemplate jdbcOf() {
        return new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void constructorSeedsTheTwoRealHistoricalRuleVersions() {
        List<ScheduleRule> all = service.list();
        assertThat(all).extracting(ScheduleRule::id)
            .containsExactly("rule-2026-03-13", "rule-2026-07-27"); // ordered by effectiveFrom
    }

    @Test
    void seededRuleRetainsItsOriginalDayOwnersAndLabel() {
        ScheduleRule original = service.list().get(0);
        assertThat(original.label()).isEqualTo("Original 2026 custody schedule");
        assertThat(original.anchor()).isEqualTo("2026-03-13");
        assertThat(original.dayOwners()).containsEntry(0, "dad").containsEntry(2, "mom");
        assertThat(original.createdBy()).isEqualTo("seed");
    }

    @Test
    void seedingIsSkippedOnceTheTableIsNoLongerEmpty() {
        int before = service.list().size();
        new ScheduleRuleService(jdbcOf(), new ObjectMapper()); // a second instance against the same now-populated table
        assertThat(service.list()).hasSize(before);
    }

    @Test
    void savingSameEffectiveFromAsLatestAmendsInPlaceInsteadOfAppending() {
        int before = service.list().size();
        ScheduleRule latest = service.latest();

        ScheduleRule amended = service.save(
            new ScheduleRule(null, latest.effectiveFrom(), latest.effectiveFrom(),
                Map.of(0, "mom", 1, "dad"), null, 0L, null),
            "nathan");

        assertThat(service.list()).hasSize(before);
        assertThat(amended.id()).isEqualTo(latest.id()); // same row, not a new one
        assertThat(amended.dayOwners()).containsEntry(0, "mom");
        assertThat(amended.createdBy()).isEqualTo(latest.createdBy()); // amend never touches original authorship
    }

    @Test
    void savingADifferentEffectiveFromAppendsANewVersion() {
        int before = service.list().size();

        ScheduleRule created = service.save(
            new ScheduleRule(null, "2027-01-01", "2027-01-01", Map.of(0, "dad"), "New Year rule", 0L, null),
            "nathan");

        assertThat(service.list()).hasSize(before + 1);
        assertThat(service.latest().id()).isEqualTo(created.id());
        assertThat(created.createdBy()).isEqualTo("nathan");
    }

    @Test
    void earlierVersionsAreNeverMutatedOnceANewOneIsAppended() {
        ScheduleRule firstBefore = service.list().get(0);

        service.save(new ScheduleRule(null, "2027-01-01", "2027-01-01", Map.of(0, "dad"), null, 0L, null), "nathan");

        ScheduleRule firstAfter = service.list().get(0);
        assertThat(firstAfter.id()).isEqualTo(firstBefore.id());
        assertThat(firstAfter.dayOwners()).isEqualTo(firstBefore.dayOwners());
    }
}
