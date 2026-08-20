package com.cabin.orchestrator.family;

import java.util.Map;

/**
 * A versioned custody-cycle definition, mirroring family-hub.html's
 * SCHEDULE_RULES entries exactly (id, effectiveFrom, anchor, dayOwners,
 * label, createdAt, createdBy). dayOwners maps cycle-day index (0-13) to
 * "dad"/"mom" -- an explicit per-index map rather than a "home days" set,
 * same choice the frontend already made (see homeDaysToOwners()'s own
 * comment in family-hub.html).
 *
 * Rules are append-mostly and never mutated once superseded: a new
 * effectiveFrom always starts a new version, and historical days keep
 * resolving under whatever rule was actually in effect then (see
 * ScheduleRuleService.save()).
 */
public record ScheduleRule(
    String id,
    String effectiveFrom,
    String anchor,
    Map<Integer, String> dayOwners,
    String label,
    long createdAt,
    String createdBy
) {}
