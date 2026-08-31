package com.cabin.orchestrator.api;

import com.cabin.orchestrator.helpdesk.TinyHelpdeskAnswer;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Sprint 2: the Tiny Helpdesk chat endpoint (Ollama + Open WebUI deployed 2026-08-30; native panel is a later follow-up, not this endpoint's job). */
@RestController
@RequestMapping("/api/helpdesk")
@CrossOrigin
public class TinyHelpdeskController {

    private final TinyHelpdeskService service;

    public TinyHelpdeskController(TinyHelpdeskService service) {
        this.service = service;
    }

    /** POST /api/helpdesk/ask {"question": "..."} */
    @PostMapping("/ask")
    public TinyHelpdeskAnswer ask(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "");
        return service.ask(question);
    }
}
