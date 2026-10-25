package com.cabin.orchestrator.api;

import com.cabin.orchestrator.assistant.IntentProposalService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * HO-2026-10-16 step 3 (W-75): {@code POST /api/helpdesk/propose {"text": "..."}}. Behind
 * {@code cabin.ask.intent.enabled} (default false: the route answers 404 as if it did not exist), administrator
 * session only, and it only ever returns a proposal; no executor exists in this path. {@code /api/helpdesk/ask}
 * and its citations are untouched. Gated by WebConfig's existing {@code /api/helpdesk/**} pattern, so a write by
 * an anonymous, guest or demo caller is refused before this class runs.
 */
@RestController
@RequestMapping("/api/helpdesk")
@CrossOrigin
public class HelpdeskProposeController {

    private final IntentProposalService service;
    private final int maxInputChars;

    public HelpdeskProposeController(IntentProposalService service,
                                     @Value("${cabin.ask.intent.max-input-chars:500}") int maxInputChars) {
        this.service = service;
        this.maxInputChars = maxInputChars;
    }

    @PostMapping("/propose")
    public ResponseEntity<?> propose(@RequestBody Map<String, String> body, HttpServletRequest request) {
        if (!service.enabled()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Not found"));
        }
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Action proposals are administrator only."));
        }
        String text = body == null ? null : body.get("text");
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is required"));
        }
        if (text.length() > maxInputChars) {
            return ResponseEntity.badRequest().body(Map.of("error", "text is longer than " + maxInputChars + " characters"));
        }
        String principal = (String) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        return ResponseEntity.ok(service.propose(text.trim(), role, principal));
    }
}
