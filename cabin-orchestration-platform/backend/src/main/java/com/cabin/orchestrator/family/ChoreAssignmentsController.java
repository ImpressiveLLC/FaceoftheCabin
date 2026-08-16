package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Which child owns which chore, and when. Same Map<String,Object> body
 * convention and client-supplied actorId as ChoreDefinitionsController —
 * see that controller's javadoc.
 *
 * A one-day custom assignment is just POST with recurrence="ONE_DAY" and
 * effectiveStart=that date — no separate endpoint needed. A reassignment
 * (move a chore to a different child) is PATCH {childId: newChildId} —
 * also no separate endpoint, since it's the same partial-update
 * ChoreAssignmentService.update() already supports.
 */
@RestController
@RequestMapping("/api/chores/assignments")
@CrossOrigin
public class ChoreAssignmentsController {

    private final ChoreAssignmentService assignments;

    public ChoreAssignmentsController(ChoreAssignmentService assignments) { this.assignments = assignments; }

    @GetMapping
    public List<ChoreAssignment> list(@RequestParam(required = false) String childId,
                                       @RequestParam(required = false) String date) {
        if (date != null && !date.isBlank()) return assignments.applicableOn(childId, date);
        return assignments.list(childId);
    }

    @PostMapping
    public ChoreAssignment create(@RequestBody Map<String, Object> body) {
        ChoreAssignment a = fromBody(null, body);
        if (a.choreDefinitionId() == null || a.choreDefinitionId().isBlank()
                || a.childId() == null || a.childId().isBlank()) {
            throw new IllegalArgumentException("choreDefinitionId and childId are required");
        }
        return assignments.create(a, actorId(body));
    }

    @PatchMapping("/{id}")
    public ChoreAssignment update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        ChoreAssignment updated = assignments.update(id, fromBody(id, body), actorId(body));
        if (updated == null) throw new IllegalArgumentException("No assignment with id " + id);
        return updated;
    }

    @DeleteMapping("/{id}")
    public void remove(@PathVariable String id) {
        if (!assignments.remove(id)) throw new IllegalArgumentException("No assignment with id " + id);
    }

    @PostMapping("/reorder")
    @SuppressWarnings("unchecked")
    public void reorder(@RequestBody Map<String, Object> body) {
        String childId = (String) body.get("childId");
        List<String> orderedIds = (List<String>) body.get("orderedIds");
        if (childId == null || childId.isBlank() || orderedIds == null || orderedIds.isEmpty()) {
            throw new IllegalArgumentException("childId and orderedIds are required");
        }
        assignments.reorder(childId, orderedIds, actorId(body));
    }

    private String actorId(Map<String, Object> body) {
        Object actorId = body.get("actorId");
        return actorId == null ? null : actorId.toString();
    }

    // effectiveEnd: an explicit JSON null in the body (key present, value
    // null) means "clear back to ongoing" -- containsKey distinguishes
    // that from the key being absent entirely ("leave untouched"), which
    // ChoreAssignmentService.update() reads as Java null. The "" sentinel
    // is what actually carries "clear" through to the service, since Java
    // null on the patch object already means "don't touch" there.
    private ChoreAssignment fromBody(String id, Map<String, Object> body) {
        String effectiveEnd;
        if (!body.containsKey("effectiveEnd")) {
            effectiveEnd = null; // not provided -- don't touch
        } else if (body.get("effectiveEnd") == null) {
            effectiveEnd = ""; // explicit null -- clear to ongoing
        } else {
            effectiveEnd = body.get("effectiveEnd").toString();
        }
        return new ChoreAssignment(
            id,
            (String) body.get("choreDefinitionId"),
            (String) body.get("childId"),
            true,
            (String) body.get("recurrence"),
            (String) body.get("effectiveStart"),
            effectiveEnd,
            0,
            (String) body.get("location"),
            0, 0, null, null
        );
    }
}
