package com.cabin.orchestrator.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class HolidayServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private HolidayService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS holiday");
        service = new HolidayService(jdbc);
    }

    @Test
    void constructorCreatesAnEmptyTableWithNoSeedData() {
        // Unlike ScheduleRuleService, family-hub.html never had a
        // DEFAULT_HOLIDAYS array -- HOLIDAYS always started empty.
        assertThat(service.list()).isEmpty();
    }

    @Test
    void createThenListRoundTrips() {
        Holiday created = service.create(
            new Holiday(null, "2026-12-25", "Christmas Day", "mom", 0L, null), "nathan");

        assertThat(created.id()).isNotBlank();
        assertThat(created.createdBy()).isEqualTo("nathan");
        assertThat(service.list()).extracting(Holiday::id).containsExactly(created.id());
    }

    @Test
    void patchUpdatesOnlyProvidedFields() {
        Holiday created = service.create(
            new Holiday("hol-test", "2026-12-25", "Christmas Day", "mom", 0L, null), "nathan");

        Holiday updated = service.update("hol-test",
            new Holiday("hol-test", null, "Christmas", null, 0L, null), "emma");

        assertThat(updated.name()).isEqualTo("Christmas");
        assertThat(updated.date()).isEqualTo(created.date()); // untouched
        assertThat(updated.owner()).isEqualTo(created.owner()); // untouched
        assertThat(updated.createdBy()).isEqualTo("emma");
    }

    @Test
    void deleteRemovesTheRowEntirelyNotSoftDelete() {
        service.create(new Holiday("hol-del", "2026-07-04", "Fourth of July", "dad", 0L, null), "nathan");

        boolean deleted = service.delete("hol-del");

        assertThat(deleted).isTrue();
        assertThat(service.byId("hol-del")).isNull(); // hard delete -- gone, not archived
        assertThat(service.list()).isEmpty();
    }

    @Test
    void deletingAnUnknownIdReturnsFalse() {
        assertThat(service.delete("does-not-exist")).isFalse();
    }
}
