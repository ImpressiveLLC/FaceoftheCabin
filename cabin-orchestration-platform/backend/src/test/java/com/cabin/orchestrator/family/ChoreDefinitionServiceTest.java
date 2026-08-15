package com.cabin.orchestrator.family;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-08-15: the whole point of this migration is that existing
 * chore_completion rows (keyed by chore_id) keep resolving after the
 * switch from a hardcoded CHORES array to this table -- these tests exist
 * specifically to pin the seeded ids/fields to what that array actually
 * contained (17 entries, exact ids), not just "seeding produces some rows."
 */
@Testcontainers
class ChoreDefinitionServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private ChoreDefinitionService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS chore_definition");
        service = new ChoreDefinitionService(jdbc); // constructor seeds on an empty table
    }

    @Test
    void seedsExactlyTheOldHardcodedChoreIds() {
        List<ChoreDefinition> all = service.list(true);
        assertThat(all).extracting(ChoreDefinition::id).containsExactlyInAnyOrder(
            "set_table", "clear_table", "unload_dishwasher", "take_trash", "take_recycling",
            "clean_cat_box", "homework", "reading", "vacuum_room", "fold_laundry",
            "put_away_laundry", "brush_teeth", "wipe_table", "make_bed", "outfit_ready",
            "walk_frankie", "lunch_backpack");
    }

    @Test
    void seededChoreRetainsItsOriginalLabelPointsMinAgeAndTags() {
        ChoreDefinition cleanCatBox = service.byId("clean_cat_box");
        assertThat(cleanCatBox.label()).isEqualTo("Clean the cat box");
        assertThat(cleanCatBox.points()).isEqualTo(1);
        assertThat(cleanCatBox.minAge()).isEqualTo(9);
        assertThat(cleanCatBox.tags()).containsExactly("pet");
        assertThat(cleanCatBox.active()).isTrue();
    }

    @Test
    void seedingIsSkippedOnceTheTableIsNoLongerEmpty() {
        int before = service.list(true).size();
        new ChoreDefinitionService(jdbcOf()); // a second instance against the same now-populated table
        assertThat(service.list(true)).hasSize(before);
    }

    private JdbcTemplate jdbcOf() {
        return new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void createAddsACustomChore() {
        ChoreDefinition created = service.create(
            new ChoreDefinition("feed_fish", "Feed the fish", 1, 6, List.of("pet", "daily"), true, 0, 0, 0, null, null),
            "sam");

        assertThat(created.id()).isEqualTo("feed_fish");
        assertThat(created.createdBy()).isEqualTo("sam");
        assertThat(service.list(false)).extracting(ChoreDefinition::id).contains("feed_fish");
    }

    @Test
    void createDefaultsPointsAndMinAgeWhenOmitted() {
        ChoreDefinition created = service.create(
            new ChoreDefinition("mystery_chore", "A new chore", null, null, null, true, 0, 0, 0, null, null),
            "sam");

        assertThat(created.points()).isEqualTo(1);
        assertThat(created.minAge()).isEqualTo(0);
    }

    @Test
    void updateOnlyAppliesProvidedFields() {
        service.create(new ChoreDefinition("test_chore", "Original label", 2, 8, List.of("house"), true, 0, 0, 0, null, null), "sam");

        ChoreDefinition updated = service.update("test_chore",
            new ChoreDefinition("test_chore", null, 5, null, null, true, 0, 0, 0, null, null), "emma");

        assertThat(updated.label()).isEqualTo("Original label");
        assertThat(updated.points()).isEqualTo(5);
        assertThat(updated.minAge()).isEqualTo(8);
        assertThat(updated.tags()).containsExactly("house");
        assertThat(updated.updatedBy()).isEqualTo("emma");
    }

    @Test
    void archiveIsSoftAndExcludesFromTheActiveListButNotByIdLookup() {
        boolean archived = service.setActive("clean_cat_box", false, "sam");

        assertThat(archived).isTrue();
        assertThat(service.list(false)).extracting(ChoreDefinition::id).doesNotContain("clean_cat_box");
        assertThat(service.list(true)).extracting(ChoreDefinition::id).contains("clean_cat_box");
        assertThat(service.byId("clean_cat_box").active()).isFalse();
    }

    @Test
    void restoreReactivatesAnArchivedChore() {
        service.setActive("clean_cat_box", false, "sam");
        service.setActive("clean_cat_box", true, "sam");

        assertThat(service.list(false)).extracting(ChoreDefinition::id).contains("clean_cat_box");
    }

    @Test
    void reorderUpdatesDisplayOrderForGivenIds() {
        service.reorder(List.of("walk_frankie", "homework"), "sam");

        ChoreDefinition walk = service.byId("walk_frankie");
        ChoreDefinition homework = service.byId("homework");
        assertThat(walk.displayOrder()).isEqualTo(0);
        assertThat(homework.displayOrder()).isEqualTo(1);
    }
}
