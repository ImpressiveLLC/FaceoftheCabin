package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The chore library — reusable chore definitions, independent of who
 * they're currently assigned to (see ChoreAssignmentsController). Same
 * Map<String,Object> request-body convention as ProfilesController/
 * NotesController: actorId is client-supplied (family_profiles id), same
 * "same trust as a fridge note" model GoogleAuthInterceptor's own javadoc
 * describes — not derived from the Google token the way RulesController's
 * stricter automation-workflow actor identity is, since this is a
 * family-facing feature like notes/profiles, not an unattended automation
 * surface.
 */
@RestController
@RequestMapping("/api/chores/definitions")
@CrossOrigin
public class ChoreDefinitionsController {

    private final ChoreDefinitionService definitions;

    public ChoreDefinitionsController(ChoreDefinitionService definitions) { this.definitions = definitions; }

    @GetMapping
    public List<ChoreDefinition> list(@RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
        return definitions.list(includeArchived);
    }

    @PostMapping
    public ChoreDefinition create(@RequestBody Map<String, Object> body) {
        String id = requireId(body);
        ChoreDefinition c = fromBody(id, body);
        if (c.label() == null || c.label().isBlank()) {
            throw new IllegalArgumentException("label is required");
        }
        return definitions.create(c, actorId(body));
    }

    @PatchMapping("/{id}")
    public ChoreDefinition update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        ChoreDefinition updated = definitions.update(id, fromBody(id, body), actorId(body));
        if (updated == null) throw new IllegalArgumentException("No chore with id " + id);
        return updated;
    }

    /** Archive, not a hard delete — same soft-delete convention as ProfilesController.delete(), preserves history for completed/assigned rows that still reference this chore. */
    @DeleteMapping("/{id}")
    public void archive(@PathVariable String id, @RequestParam(required = false) String actorId) {
        if (!definitions.setActive(id, false, actorId)) throw new IllegalArgumentException("No chore with id " + id);
    }

    @PostMapping("/{id}/restore")
    public void restore(@PathVariable String id, @RequestParam(required = false) String actorId) {
        if (!definitions.setActive(id, true, actorId)) throw new IllegalArgumentException("No chore with id " + id);
    }

    @PostMapping("/reorder")
    @SuppressWarnings("unchecked")
    public void reorder(@RequestBody Map<String, Object> body) {
        List<String> orderedIds = (List<String>) body.get("orderedIds");
        if (orderedIds == null || orderedIds.isEmpty()) throw new IllegalArgumentException("orderedIds is required");
        definitions.reorder(orderedIds, actorId(body));
    }

    private String requireId(Map<String, Object> body) {
        Object id = body.get("id");
        if (id == null || id.toString().isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        return id.toString();
    }

    private String actorId(Map<String, Object> body) {
        Object actorId = body.get("actorId");
        return actorId == null ? null : actorId.toString();
    }

    @SuppressWarnings("unchecked")
    private ChoreDefinition fromBody(String id, Map<String, Object> body) {
        return new ChoreDefinition(
            id,
            (String) body.get("label"),
            body.get("points") == null ? null : ((Number) body.get("points")).intValue(),
            body.get("minAge") == null ? null : ((Number) body.get("minAge")).intValue(),
            (List<String>) body.get("tags"),
            true, 0, 0, 0, null, null
        );
    }
}
