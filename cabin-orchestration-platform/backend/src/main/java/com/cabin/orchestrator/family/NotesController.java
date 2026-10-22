package com.cabin.orchestrator.family;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notes")
@CrossOrigin
public class NotesController {

    private final FamilyNoteService notes;

    public NotesController(FamilyNoteService notes) { this.notes = notes; }

    @GetMapping
    public List<FamilyNote> list() {
        return notes.recent();
    }

    @PostMapping
    public FamilyNote add(@RequestBody Map<String, String> body) {
        String authorId = body.get("authorId");
        String text = body.get("text");
        return notes.add(authorId, text, body.get("kind"), body.get("dateType"), body.get("date"), body.get("replyToId"));
    }
}
