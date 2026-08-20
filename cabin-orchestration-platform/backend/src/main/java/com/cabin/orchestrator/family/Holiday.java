package com.cabin.orchestrator.family;

/**
 * A single-date custody override, mirroring family-hub.html's HOLIDAYS
 * entries exactly (id, date, name, owner, createdAt, createdBy). No
 * recurrence concept yet -- a holiday only ever applies to the literal
 * `date` it was saved with (see ownerOfDate()/holidayForDate() in
 * family-hub.html). Recurring patterns (yearly/biennial) are a later,
 * separately-scoped phase -- see docs/ontology.yaml's holiday_override
 * entity notes.
 */
public record Holiday(
    String id,
    String date,
    String name,
    String owner,
    long createdAt,
    String createdBy
) {}
