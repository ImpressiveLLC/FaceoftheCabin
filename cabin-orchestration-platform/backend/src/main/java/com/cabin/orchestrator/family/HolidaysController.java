package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Same Map<String,Object> request-body / client-supplied actorId convention as ScheduleRulesController. */
@RestController
@RequestMapping("/api/schedule/holidays")
@CrossOrigin
public class HolidaysController {

    private final HolidayService holidays;

    public HolidaysController(HolidayService holidays) { this.holidays = holidays; }

    @GetMapping
    public List<Holiday> list() { return holidays.list(); }

    @PostMapping
    public Holiday create(@RequestBody Map<String, Object> body) {
        Holiday h = fromBody((String) body.get("id"), body);
        if (h.date() == null || h.date().isBlank() || h.name() == null || h.name().isBlank()) {
            throw new IllegalArgumentException("date and name are required");
        }
        return holidays.create(h, actorId(body));
    }

    @PatchMapping("/{id}")
    public Holiday update(@PathVariable String id, @RequestBody Map<String, Object> body) {
        Holiday updated = holidays.update(id, fromBody(id, body), actorId(body));
        if (updated == null) throw new IllegalArgumentException("No holiday with id " + id);
        return updated;
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable String id) {
        if (!holidays.delete(id)) throw new IllegalArgumentException("No holiday with id " + id);
    }

    private String actorId(Map<String, Object> body) {
        Object actorId = body.get("actorId");
        return actorId == null ? null : actorId.toString();
    }

    private Holiday fromBody(String id, Map<String, Object> body) {
        return new Holiday(
            id,
            (String) body.get("date"),
            (String) body.get("name"),
            (String) body.get("owner"),
            0L, null);
    }
}
