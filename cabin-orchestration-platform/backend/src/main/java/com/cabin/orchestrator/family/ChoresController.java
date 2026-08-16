package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 2026-08-15: POST /completion/toggle removed — see
 * ChoreCompletionService.setDone()'s javadoc for why a toggle isn't safe
 * against concurrent devices or retries. PUT /completions/{date}/{childId}/
 * {assignmentId} replaces it: the caller states the target state
 * explicitly, so a repeated identical request is a no-op, not a second
 * flip. Resolves through ChoreAssignmentService because the caller only
 * knows the assignment it's looking at, not the underlying chore
 * definition id chore_completion is actually keyed by (kept as chore_id,
 * unchanged, so existing completion history stays intact).
 */
@RestController
@RequestMapping("/api/chores")
@CrossOrigin
public class ChoresController {

    private final ChoreCompletionService completion;
    private final ChoreAssignmentService assignments;

    public ChoresController(ChoreCompletionService completion, ChoreAssignmentService assignments) {
        this.completion = completion;
        this.assignments = assignments;
    }

    @GetMapping("/completion")
    public Map<String, Map<String, Boolean>> completionState() {
        return completion.all();
    }

    @PutMapping("/completions/{date}/{childId}/{assignmentId}")
    public Map<String, Object> setCompletion(@PathVariable String date, @PathVariable String childId,
                                              @PathVariable String assignmentId, @RequestBody Map<String, Object> body) {
        ChoreAssignment assignment = assignments.byId(assignmentId);
        if (assignment == null) throw new IllegalArgumentException("No assignment with id " + assignmentId);
        if (!childId.equals(assignment.childId())) {
            throw new IllegalArgumentException("Assignment " + assignmentId + " does not belong to child " + childId);
        }
        Object doneVal = body.get("done");
        if (!(doneVal instanceof Boolean done)) {
            throw new IllegalArgumentException("done (boolean) is required");
        }
        completion.setDone(date, childId, assignment.choreDefinitionId(), done);
        return Map.of("date", date, "childId", childId, "assignmentId", assignmentId,
            "choreId", assignment.choreDefinitionId(), "done", done);
    }
}
