package com.cabin.orchestrator.family;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Same JdbcTemplate + CREATE TABLE IF NOT EXISTS self-migration pattern as
 * FamilyProfileService/FamilyNoteService/ChoreCompletionService — no
 * migration framework in this project yet.
 *
 * DELETE is a soft archive (active=false), not a hard row removal, same
 * rationale as FamilyProfileService: an archived chore can still be
 * resolved by id to render its label/tags on historical
 * chore_completion/chore_assignment rows that reference it, even after a
 * parent retires it from the active library.
 */
@Service
public class ChoreDefinitionService {

    private final JdbcTemplate jdbc;

    // Table creation runs directly in the constructor, not behind
    // @PostConstruct -- see FamilyProfileService's constructor comment for
    // why (a @PostConstruct method never fires on a plain `new` outside a
    // Spring context, which is exactly how this project's Testcontainers
    // tests construct every service).
    public ChoreDefinitionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chore_definition (
              id           VARCHAR(128) PRIMARY KEY,
              label        VARCHAR(256) NOT NULL,
              points       INTEGER NOT NULL DEFAULT 1,
              min_age      INTEGER NOT NULL DEFAULT 0,
              tags         VARCHAR(512),
              active       BOOLEAN NOT NULL DEFAULT true,
              display_order INTEGER NOT NULL DEFAULT 0,
              created_at   BIGINT NOT NULL,
              updated_at   BIGINT NOT NULL,
              created_by   VARCHAR(128),
              updated_by   VARCHAR(128)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chore_definition_active ON chore_definition (active, display_order)");
        seedIfEmpty();
    }

    // Migrates family-hub.html's old hardcoded CHORES const (17 entries) --
    // ids preserved exactly so existing chore_completion rows (keyed by
    // chore_id) keep resolving after this migration. Seeds once per new
    // instance (empty table), matching FamilyProfileService's own
    // seedIfEmpty() precedent -- from this point on the library is fully
    // editable data, not a source-code constant.
    private void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chore_definition", Integer.class);
        if (count != null && count > 0) return;

        long now = System.currentTimeMillis();
        Object[][] seed = {
            {"set_table",         "Set the table & help serve",     1, 6, "meal,daily"},
            {"clear_table",       "Clear table & load dishwasher",  1, 6, "meal,daily"},
            {"unload_dishwasher", "Unload dishwasher",              1, 6, "kitchen"},
            {"take_trash",        "Take out trash",                 1, 6, "house"},
            {"take_recycling",    "Take out recycling",             1, 6, "house"},
            {"clean_cat_box",     "Clean the cat box",              1, 9, "pet"},
            {"homework",          "Do homework",                    1, 6, "school,daily"},
            {"reading",           "Read 20 minutes",                1, 6, "school,daily"},
            {"vacuum_room",       "Vacuum one room",                1, 6, "house"},
            {"fold_laundry",      "Fold laundry",                   1, 6, "laundry"},
            {"put_away_laundry",  "Put away own laundry",           1, 6, "laundry,daily"},
            {"brush_teeth",       "Brush teeth 2+ minutes",         1, 6, "routine,daily"},
            {"wipe_table",        "Wipe table and chairs",          1, 6, "meal"},
            {"make_bed",          "Make the bed",                   1, 6, "routine,daily"},
            {"outfit_ready",      "Get outfit ready for tomorrow",  1, 6, "routine,daily"},
            {"walk_frankie",      "Walk Frankie 🐶",      1, 6, "pet"},
            {"lunch_backpack",    "Help with lunch & load backpack",1, 6, "school,daily"},
        };
        for (int i = 0; i < seed.length; i++) {
            Object[] c = seed[i];
            jdbc.update("""
                INSERT INTO chore_definition
                  (id, label, points, min_age, tags, active, display_order, created_at, updated_at, created_by, updated_by)
                VALUES (?,?,?,?,?,true,?,?,?,?,?)
                """,
                c[0], c[1], c[2], c[3], c[4], i, now, now, "seed", "seed");
        }
    }

    public List<ChoreDefinition> list(boolean includeArchived) {
        String sql = includeArchived
            ? "SELECT * FROM chore_definition ORDER BY display_order, label"
            : "SELECT * FROM chore_definition WHERE active = true ORDER BY display_order, label";
        return jdbc.queryForList(sql).stream().map(this::fromRow).toList();
    }

    public ChoreDefinition byId(String id) {
        List<ChoreDefinition> rows = jdbc.queryForList(
                "SELECT * FROM chore_definition WHERE id = ?", id)
            .stream().map(this::fromRow).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public ChoreDefinition create(ChoreDefinition c, String actorId) {
        long now = System.currentTimeMillis();
        Integer maxSort = jdbc.queryForObject("SELECT COALESCE(MAX(display_order), -1) FROM chore_definition", Integer.class);
        int displayOrder = (maxSort == null ? -1 : maxSort) + 1;
        int points = c.points() != null ? c.points() : 1;
        int minAge = c.minAge() != null ? c.minAge() : 0;
        jdbc.update("""
            INSERT INTO chore_definition
              (id, label, points, min_age, tags, active, display_order, created_at, updated_at, created_by, updated_by)
            VALUES (?,?,?,?,?,true,?,?,?,?,?)
            """,
            c.id(), c.label(), points, minAge, joinTags(c.tags()), displayOrder, now, now, actorId, actorId);
        return byId(c.id());
    }

    /** Partial update — only non-null fields on the incoming record are applied. tags=null leaves tags unchanged; tags=[] clears them. */
    public ChoreDefinition update(String id, ChoreDefinition patch, String actorId) {
        ChoreDefinition existing = byId(id);
        if (existing == null) return null;
        ChoreDefinition merged = new ChoreDefinition(
            id,
            patch.label() != null ? patch.label() : existing.label(),
            patch.points() != null ? patch.points() : existing.points(),
            patch.minAge() != null ? patch.minAge() : existing.minAge(),
            patch.tags() != null ? patch.tags() : existing.tags(),
            existing.active(),
            existing.displayOrder(),
            existing.createdAt(),
            System.currentTimeMillis(),
            existing.createdBy(),
            actorId
        );
        jdbc.update("""
            UPDATE chore_definition SET
              label=?, points=?, min_age=?, tags=?, updated_at=?, updated_by=?
            WHERE id = ?
            """,
            merged.label(), merged.points(), merged.minAge(), joinTags(merged.tags()),
            merged.updatedAt(), merged.updatedBy(), id);
        return merged;
    }

    public boolean setActive(String id, boolean active, String actorId) {
        int rows = jdbc.update(
            "UPDATE chore_definition SET active = ?, updated_at = ?, updated_by = ? WHERE id = ?",
            active, System.currentTimeMillis(), actorId, id);
        return rows > 0;
    }

    public void reorder(List<String> orderedIds, String actorId) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < orderedIds.size(); i++) {
            jdbc.update("UPDATE chore_definition SET display_order = ?, updated_at = ?, updated_by = ? WHERE id = ?",
                i, now, actorId, orderedIds.get(i));
        }
    }

    private ChoreDefinition fromRow(Map<String, Object> row) {
        return new ChoreDefinition(
            (String) row.get("id"),
            (String) row.get("label"),
            ((Number) row.get("points")).intValue(),
            ((Number) row.get("min_age")).intValue(),
            splitTags((String) row.get("tags")),
            (Boolean) row.get("active"),
            ((Number) row.get("display_order")).intValue(),
            ((Number) row.get("created_at")).longValue(),
            ((Number) row.get("updated_at")).longValue(),
            (String) row.get("created_by"),
            (String) row.get("updated_by"));
    }

    private static String joinTags(List<String> tags) {
        return tags == null || tags.isEmpty() ? "" : String.join(",", tags);
    }

    private static List<String> splitTags(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
    }
}
