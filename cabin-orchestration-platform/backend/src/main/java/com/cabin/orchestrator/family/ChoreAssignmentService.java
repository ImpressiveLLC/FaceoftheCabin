package com.cabin.orchestrator.family;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Same JdbcTemplate + CREATE TABLE IF NOT EXISTS self-migration pattern as
 * ChoreDefinitionService/FamilyProfileService — no migration framework in
 * this project yet.
 *
 * Unlike chore_definition (soft-archived, since completion history needs
 * to keep resolving a chore's label), an assignment can be hard-deleted:
 * chore_completion is keyed by (day_key, kid_id, chore_id), never by
 * assignment id, so removing an assignment orphans nothing.
 */
@Service
public class ChoreAssignmentService {

    private final JdbcTemplate jdbc;
    private final FamilyProfileService profiles;

    // Table creation runs directly in the constructor, not behind
    // @PostConstruct -- see FamilyProfileService's constructor comment for
    // why (a @PostConstruct method never fires on a plain `new` outside a
    // Spring context, which is exactly how this project's Testcontainers
    // tests construct every service).
    public ChoreAssignmentService(JdbcTemplate jdbc, FamilyProfileService profiles) {
        this.jdbc = jdbc;
        this.profiles = profiles;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS chore_assignment (
              id                  VARCHAR(128) PRIMARY KEY,
              chore_definition_id VARCHAR(128) NOT NULL,
              child_id            VARCHAR(128) NOT NULL,
              active              BOOLEAN NOT NULL DEFAULT true,
              recurrence          VARCHAR(16) NOT NULL DEFAULT 'DAILY',
              effective_start     VARCHAR(10) NOT NULL,
              effective_end       VARCHAR(10),
              display_order       INTEGER NOT NULL DEFAULT 0,
              location            VARCHAR(32),
              created_at          BIGINT NOT NULL,
              updated_at          BIGINT NOT NULL,
              created_by          VARCHAR(128),
              updated_by          VARCHAR(128)
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_chore_assignment_child ON chore_assignment (child_id, active)");
        seedIfEmpty();
    }

    // Migrates family-hub.html's old defaultAssignments(dayKey, kidId) --
    // computed live from age + `dayOfMonth % rotating.length` -- into real,
    // editable rows. Seeds once per new instance (empty table): each
    // existing kid gets exactly the chores that algorithm would have
    // picked for TODAY (the day this migration first runs), as ongoing
    // DAILY assignments. That means nothing visibly changes for anyone the
    // moment this ships -- the seeded set matches what was already on
    // screen -- but going forward it's stored, human-editable data instead
    // of being silently recomputed (and silently changing) every day.
    // Deliberately does NOT reproduce the old day-to-day rotation itself;
    // per this feature's own design brief, the rotation was a workaround
    // for not having real assignment data, not a behavior worth
    // preserving now that real data exists.
    private void seedIfEmpty() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM chore_assignment", Integer.class);
        if (count != null && count > 0) return;

        String todayKey = LocalDate.now().toString();
        int dayOfMonth = LocalDate.now().getDayOfMonth();
        long now = System.currentTimeMillis();

        for (FamilyProfile kid : profiles.list()) {
            if (!"kid".equals(kid.role()) || kid.age() == null) continue;
            List<String> daily = List.of("brush_teeth", "make_bed", "reading");
            List<String> rotating = kid.age() >= 9
                ? List.of("clear_table", "unload_dishwasher", "take_trash", "walk_frankie", "homework")
                : List.of("set_table", "wipe_table", "outfit_ready", "lunch_backpack", "put_away_laundry");
            String todaysRotatingPick = rotating.get(dayOfMonth % rotating.size());

            int order = 0;
            for (String choreId : daily) {
                insertSeedAssignment(kid.id(), choreId, todayKey, order++, now);
            }
            insertSeedAssignment(kid.id(), todaysRotatingPick, todayKey, order, now);
        }
    }

    private void insertSeedAssignment(String childId, String choreDefinitionId, String effectiveStart, int order, long now) {
        jdbc.update("""
            INSERT INTO chore_assignment
              (id, chore_definition_id, child_id, active, recurrence, effective_start, effective_end,
               display_order, location, created_at, updated_at, created_by, updated_by)
            VALUES (?,?,?,true,'DAILY',?,NULL,?,NULL,?,?,?,?)
            """,
            UUID.randomUUID().toString(), choreDefinitionId, childId, effectiveStart, order, now, now, "seed", "seed");
    }

    /** All active assignments, optionally scoped to one child. */
    public List<ChoreAssignment> list(String childId) {
        List<Map<String, Object>> rows = childId == null || childId.isBlank()
            ? jdbc.queryForList("SELECT * FROM chore_assignment WHERE active = true ORDER BY child_id, display_order")
            : jdbc.queryForList("SELECT * FROM chore_assignment WHERE active = true AND child_id = ? ORDER BY display_order", childId);
        return rows.stream().map(this::fromRow).toList();
    }

    public ChoreAssignment byId(String id) {
        List<ChoreAssignment> rows = jdbc.queryForList("SELECT * FROM chore_assignment WHERE id = ?", id)
            .stream().map(this::fromRow).toList();
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Assignments that apply on the given date, for one child — DAILY within [start,end], or ONE_DAY matching exactly. */
    public List<ChoreAssignment> applicableOn(String childId, String dateKey) {
        return list(childId).stream().filter(a -> applies(a, dateKey)).toList();
    }

    private boolean applies(ChoreAssignment a, String dateKey) {
        if (dateKey.compareTo(a.effectiveStart()) < 0) return false;
        if ("ONE_DAY".equals(a.recurrence())) return dateKey.equals(a.effectiveStart());
        return a.effectiveEnd() == null || dateKey.compareTo(a.effectiveEnd()) <= 0;
    }

    public ChoreAssignment create(ChoreAssignment a, String actorId) {
        long now = System.currentTimeMillis();
        String id = (a.id() == null || a.id().isBlank()) ? UUID.randomUUID().toString() : a.id();
        Integer maxSort = jdbc.queryForObject(
            "SELECT COALESCE(MAX(display_order), -1) FROM chore_assignment WHERE child_id = ?", Integer.class, a.childId());
        int displayOrder = (maxSort == null ? -1 : maxSort) + 1;
        String recurrence = a.recurrence() == null || a.recurrence().isBlank() ? "DAILY" : a.recurrence();
        String effectiveStart = a.effectiveStart() == null || a.effectiveStart().isBlank()
            ? LocalDate.now().toString() : a.effectiveStart();
        // "" (the same "explicit clear" sentinel update() reads) means the
        // same thing as null on create -- there is no prior value to
        // clear, so both just mean "ongoing" (no end date) here.
        String effectiveEnd = "ONE_DAY".equals(recurrence) ? effectiveStart
            : (a.effectiveEnd() == null || a.effectiveEnd().isBlank() ? null : a.effectiveEnd());

        jdbc.update("""
            INSERT INTO chore_assignment
              (id, chore_definition_id, child_id, active, recurrence, effective_start, effective_end,
               display_order, location, created_at, updated_at, created_by, updated_by)
            VALUES (?,?,?,true,?,?,?,?,?,?,?,?,?)
            """,
            id, a.choreDefinitionId(), a.childId(), recurrence, effectiveStart, effectiveEnd,
            displayOrder, a.location(), now, now, actorId, actorId);
        return byId(id);
    }

    /**
     * Partial update — also how a reassignment (move to a different child)
     * happens: PATCH childId. Only non-null fields on the incoming record
     * are applied, same convention as ChoreDefinitionService/
     * FamilyProfileService's update() methods, with one addition:
     * effectiveEnd also accepts the sentinel empty string "" (distinct
     * from Java null, which means "leave untouched" here) to explicitly
     * clear a temporary end date back to "ongoing" — the same empty-
     * string-means-clear convention already used elsewhere in this
     * codebase (e.g. blinkCameraMapRaw). ChoreAssignmentsController is
     * what actually produces that sentinel from an explicit JSON null.
     */
    public ChoreAssignment update(String id, ChoreAssignment patch, String actorId) {
        ChoreAssignment existing = byId(id);
        if (existing == null) return null;
        String nextEffectiveEnd = existing.effectiveEnd();
        if (patch.effectiveEnd() != null) {
            nextEffectiveEnd = patch.effectiveEnd().isEmpty() ? null : patch.effectiveEnd();
        }
        ChoreAssignment merged = new ChoreAssignment(
            id,
            patch.choreDefinitionId() != null ? patch.choreDefinitionId() : existing.choreDefinitionId(),
            patch.childId() != null ? patch.childId() : existing.childId(),
            existing.active(),
            patch.recurrence() != null ? patch.recurrence() : existing.recurrence(),
            patch.effectiveStart() != null ? patch.effectiveStart() : existing.effectiveStart(),
            nextEffectiveEnd,
            existing.displayOrder(),
            patch.location() != null ? patch.location() : existing.location(),
            existing.createdAt(),
            System.currentTimeMillis(),
            existing.createdBy(),
            actorId
        );
        jdbc.update("""
            UPDATE chore_assignment SET
              chore_definition_id=?, child_id=?, recurrence=?, effective_start=?, effective_end=?, location=?,
              updated_at=?, updated_by=?
            WHERE id = ?
            """,
            merged.choreDefinitionId(), merged.childId(), merged.recurrence(), merged.effectiveStart(),
            merged.effectiveEnd(), merged.location(), merged.updatedAt(), merged.updatedBy(), id);
        return merged;
    }

    public void reorder(String childId, List<String> orderedIds, String actorId) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < orderedIds.size(); i++) {
            jdbc.update("UPDATE chore_assignment SET display_order = ?, updated_at = ?, updated_by = ? WHERE id = ? AND child_id = ?",
                i, now, actorId, orderedIds.get(i), childId);
        }
    }

    public boolean remove(String id) {
        return jdbc.update("DELETE FROM chore_assignment WHERE id = ?", id) > 0;
    }

    private ChoreAssignment fromRow(Map<String, Object> row) {
        return new ChoreAssignment(
            (String) row.get("id"),
            (String) row.get("chore_definition_id"),
            (String) row.get("child_id"),
            (Boolean) row.get("active"),
            (String) row.get("recurrence"),
            (String) row.get("effective_start"),
            (String) row.get("effective_end"),
            ((Number) row.get("display_order")).intValue(),
            (String) row.get("location"),
            ((Number) row.get("created_at")).longValue(),
            ((Number) row.get("updated_at")).longValue(),
            (String) row.get("created_by"),
            (String) row.get("updated_by"));
    }
}
