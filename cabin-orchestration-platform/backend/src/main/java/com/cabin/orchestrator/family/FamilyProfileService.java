package com.cabin.orchestrator.family;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Same JdbcTemplate + CREATE TABLE IF NOT EXISTS self-migration pattern as
 * FamilyNoteService/ChoreCompletionService — no migration framework in this
 * project yet.
 *
 * DELETE is a soft delete (active=false), not a hard row removal — an
 * archived profile can still be resolved by id for historical family_note
 * authorship (see docs/ontology.yaml's family_note entity: "a newly added
 * guest's historical notes resolve correctly everywhere" is part of the
 * contract this exists to satisfy). list() only returns active profiles;
 * byId() (used to resolve note authors) returns archived ones too.
 */
@Service
public class FamilyProfileService {

    private final JdbcTemplate jdbc;

    public FamilyProfileService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS family_profiles (
              id         VARCHAR(128) PRIMARY KEY,
              name       VARCHAR(128) NOT NULL,
              role       VARCHAR(32) NOT NULL,
              birthday   VARCHAR(16),
              avatar     VARCHAR(16),
              color      VARCHAR(16),
              age        INTEGER,
              type       VARCHAR(64),
              relation   VARCHAR(64),
              sort_order INTEGER NOT NULL DEFAULT 0,
              active     BOOLEAN NOT NULL DEFAULT true,
              created_at BIGINT NOT NULL,
              updated_at BIGINT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_family_profiles_active ON family_profiles (active, sort_order)");
        seedIfEmpty();
    }

    // Seeds once per new instance (empty table), not something every
    // browser/device does independently — matches DEFAULT_PROFILES in
    // family-hub.html exactly so an existing deployment's first sync
    // doesn't appear to "lose" anyone relative to what was already on
    // screen from the localStorage fallback.
    private void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM family_profiles", Integer.class);
        if (count != null && count > 0) return;

        long now = System.currentTimeMillis();
        Object[][] defaults = {
            {"sam", "Sam", "kid", "09/24", "🧒", "#4AACE8", 9, null, null},
            {"emma", "Emma", "kid", "09/10", "👧", "#C87BC8", 6, null, null},
            {"frankie", "Frankie", "pet", "08/22", "🐕", null, null, "dog", null},
            {"nathan", "Nathan", "parent", null, "👨", "#C8A44A", null, null, null},
        };
        for (int i = 0; i < defaults.length; i++) {
            Object[] d = defaults[i];
            jdbc.update("""
                INSERT INTO family_profiles
                  (id, name, role, birthday, avatar, color, age, type, relation, sort_order, active, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                d[0], d[1], d[2], d[3], d[4], d[5], d[6], d[7], d[8], i, true, now, now);
        }
    }

    public List<FamilyProfile> list() {
        return jdbc.queryForList(
                "SELECT * FROM family_profiles WHERE active = true ORDER BY sort_order, created_at")
            .stream().map(this::fromRow).toList();
    }

    public FamilyProfile byId(String id) {
        List<FamilyProfile> rows = jdbc.queryForList(
                "SELECT * FROM family_profiles WHERE id = ?", id)
            .stream().map(this::fromRow).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    public FamilyProfile create(FamilyProfile p) {
        long now = System.currentTimeMillis();
        Integer maxSort = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), -1) FROM family_profiles", Integer.class);
        int sortOrder = (maxSort == null ? -1 : maxSort) + 1;
        jdbc.update("""
            INSERT INTO family_profiles
              (id, name, role, birthday, avatar, color, age, type, relation, sort_order, active, created_at, updated_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,true,?,?)
            ON CONFLICT (id) DO UPDATE SET
              name = EXCLUDED.name, role = EXCLUDED.role, birthday = EXCLUDED.birthday,
              avatar = EXCLUDED.avatar, color = EXCLUDED.color, age = EXCLUDED.age,
              type = EXCLUDED.type, relation = EXCLUDED.relation, active = true,
              updated_at = EXCLUDED.updated_at
            """,
            p.id(), p.name(), p.role(), p.birthday(), p.avatar(), p.color(), p.age(), p.type(), p.relation(),
            sortOrder, now, now);
        return byId(p.id());
    }

    /** Partial update — only non-null fields on the incoming record are applied. */
    public FamilyProfile update(String id, FamilyProfile patch) {
        FamilyProfile existing = byId(id);
        if (existing == null) return null;
        FamilyProfile merged = new FamilyProfile(
            id,
            patch.name() != null ? patch.name() : existing.name(),
            patch.role() != null ? patch.role() : existing.role(),
            patch.birthday() != null ? patch.birthday() : existing.birthday(),
            patch.avatar() != null ? patch.avatar() : existing.avatar(),
            patch.color() != null ? patch.color() : existing.color(),
            patch.age() != null ? patch.age() : existing.age(),
            patch.type() != null ? patch.type() : existing.type(),
            patch.relation() != null ? patch.relation() : existing.relation(),
            existing.sortOrder(),
            true,
            existing.createdAt(),
            System.currentTimeMillis()
        );
        jdbc.update("""
            UPDATE family_profiles SET
              name=?, role=?, birthday=?, avatar=?, color=?, age=?, type=?, relation=?, updated_at=?
            WHERE id = ?
            """,
            merged.name(), merged.role(), merged.birthday(), merged.avatar(), merged.color(),
            merged.age(), merged.type(), merged.relation(), merged.updatedAt(), id);
        return merged;
    }

    /** Soft delete — archives rather than removing, so historical notes still resolve. */
    public boolean archive(String id) {
        int rows = jdbc.update(
            "UPDATE family_profiles SET active = false, updated_at = ? WHERE id = ?",
            System.currentTimeMillis(), id);
        return rows > 0;
    }

    private FamilyProfile fromRow(Map<String, Object> row) {
        return new FamilyProfile(
            (String) row.get("id"),
            (String) row.get("name"),
            (String) row.get("role"),
            (String) row.get("birthday"),
            (String) row.get("avatar"),
            (String) row.get("color"),
            row.get("age") == null ? null : ((Number) row.get("age")).intValue(),
            (String) row.get("type"),
            (String) row.get("relation"),
            ((Number) row.get("sort_order")).intValue(),
            (Boolean) row.get("active"),
            ((Number) row.get("created_at")).longValue(),
            ((Number) row.get("updated_at")).longValue());
    }
}
