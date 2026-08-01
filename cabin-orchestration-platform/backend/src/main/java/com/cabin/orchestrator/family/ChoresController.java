package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/chores")
@CrossOrigin
public class ChoresController {

    private final ChoreCompletionService completion;

    public ChoresController(ChoreCompletionService completion) { this.completion = completion; }

    @GetMapping("/completion")
    public Map<String, Map<String, Boolean>> completionState() {
        return completion.all();
    }

    @PostMapping("/completion/toggle")
    public Map<String, Object> toggle(@RequestBody Map<String, String> body) {
        String dayKey = body.get("dayKey");
        String kidId = body.get("kidId");
        String choreId = body.get("choreId");
        if (dayKey == null || kidId == null || choreId == null) {
            throw new IllegalArgumentException("dayKey, kidId, and choreId are required");
        }
        boolean done = completion.toggle(dayKey, kidId, choreId);
        return Map.of("dayKey", dayKey, "kidId", kidId, "choreId", choreId, "done", done);
    }
}
