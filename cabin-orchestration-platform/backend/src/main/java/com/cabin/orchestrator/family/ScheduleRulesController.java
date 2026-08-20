package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Same Map<String,Object> request-body convention as ChoreDefinitionsController/
 * ProfilesController/NotesController: actorId is client-supplied (a
 * family_profiles id, from the shared "who's using the kiosk right now"
 * actor picker), same "same trust as a fridge note" model, not derived from
 * the Google token.
 *
 * Only GET (list) + POST (create-or-amend) -- see ScheduleRuleService.save()
 * for the amend-vs-append decision, made server-side from the payload's
 * effectiveFrom so every client gets the same correctness for free.
 */
@RestController
@RequestMapping("/api/schedule/rules")
@CrossOrigin
public class ScheduleRulesController {

    private final ScheduleRuleService rules;

    public ScheduleRulesController(ScheduleRuleService rules) { this.rules = rules; }

    @GetMapping
    public List<ScheduleRule> list() { return rules.list(); }

    @PostMapping
    @SuppressWarnings("unchecked")
    public ScheduleRule save(@RequestBody Map<String, Object> body) {
        String effectiveFrom = (String) body.get("effectiveFrom");
        if (effectiveFrom == null || effectiveFrom.isBlank()) {
            throw new IllegalArgumentException("effectiveFrom is required");
        }
        String anchor = body.get("anchor") != null ? (String) body.get("anchor") : effectiveFrom;
        Map<String, Object> rawDayOwners = (Map<String, Object>) body.getOrDefault("dayOwners", Map.of());
        Map<Integer, String> dayOwners = new java.util.LinkedHashMap<>();
        rawDayOwners.forEach((k, v) -> dayOwners.put(Integer.valueOf(k), String.valueOf(v)));

        ScheduleRule input = new ScheduleRule(
            (String) body.get("id"), effectiveFrom, anchor, dayOwners,
            (String) body.get("label"), 0L, null);
        return rules.save(input, actorId(body));
    }

    private String actorId(Map<String, Object> body) {
        Object actorId = body.get("actorId");
        return actorId == null ? null : actorId.toString();
    }
}
