package com.cabin.orchestrator.family;

/**
 * Which child owns which chore, and when — the record that was missing
 * entirely before 2026-08-15 (assignments were derived on the fly in the
 * browser from age + calendar arithmetic instead of being real, editable
 * data). One row is one standing or one-day grant of a chore to a child.
 *
 * recurrence:
 *   "DAILY"   — applies every day from effectiveStart through effectiveEnd
 *               (null effectiveEnd = ongoing/until removed).
 *   "ONE_DAY" — applies only on effectiveStart (effectiveEnd equals it).
 * Only these two are needed for today's release; the schema has room to
 * grow (a specific-days-of-week recurrence, for example) without a
 * migration, since recurrence is already a free-form string, not an enum
 * column.
 *
 * location is nullable and family-wide by default — see this record's own
 * seed/service javadoc: it describes where an action happened, not a
 * separate per-location chore list, so the same child sees and can
 * complete the same assignment at either home.
 */
public record ChoreAssignment(
    String id,
    String choreDefinitionId,
    String childId,
    boolean active,
    String recurrence,
    String effectiveStart,
    String effectiveEnd,
    int displayOrder,
    String location,
    long createdAt,
    long updatedAt,
    String createdBy,
    String updatedBy
) {}
