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
        if (authorId == null || authorId.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("authorId and text are required");
        }
        return notes.add(authorId, text.trim());
    }
}
