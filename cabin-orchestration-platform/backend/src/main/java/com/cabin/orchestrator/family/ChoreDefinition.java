package com.cabin.orchestrator.family;

import java.util.List;

/**
 * The reusable chore library — what a chore IS (label/points/minAge/tags),
 * independent of who it's currently assigned to (see ChoreAssignment) or
 * whether it was done on a given day (see ChoreCompletionService). Mirrors
 * family-hub.html's old hardcoded CHORES array shape exactly; ids are
 * preserved 1:1 from that array during the one-time seed migration (see
 * ChoreDefinitionService.seedIfEmpty()) specifically so existing
 * chore_completion rows (keyed by chore_id) keep resolving to the same
 * chore after this migration, not just the same id string.
 *
 * points/minAge are boxed (Integer), not primitive, for the same reason
 * FamilyProfile.age is boxed — ChoreDefinitionService.update() is a
 * partial update where a null field means "leave this alone," which a
 * primitive can't represent (0 would be indistinguishable from unset).
 */
public record ChoreDefinition(
    String id,
    String label,
    Integer points,
    Integer minAge,
    List<String> tags,
    boolean active,
    int displayOrder,
    long createdAt,
    long updatedAt,
    String createdBy,
    String updatedBy
) {}
