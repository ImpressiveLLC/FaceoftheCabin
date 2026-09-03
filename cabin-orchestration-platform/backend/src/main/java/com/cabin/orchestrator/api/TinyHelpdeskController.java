package com.cabin.orchestrator.api;

import com.cabin.orchestrator.helpdesk.TinyHelpdeskAnswer;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
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

    /**
     * POST /api/helpdesk/ask {"question": "..."} -- role comes from the
     * same GoogleAuthInterceptor-set request attribute CrossDomainController
     * reads (WSJF #8: never re-derived, always the one server-derived
     * source). Gating this route in WebConfig is what makes the attribute
     * non-null for a real signed-in caller at all -- see that class's own
     * comment.
     */
    @PostMapping("/ask")
    public TinyHelpdeskAnswer ask(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String question = body.getOrDefault("question", "");
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        return service.ask(question, role);
    }
}
