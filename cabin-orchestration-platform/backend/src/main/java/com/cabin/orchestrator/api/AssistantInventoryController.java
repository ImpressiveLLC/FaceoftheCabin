package com.cabin.orchestrator.api;

import com.cabin.orchestrator.assistant.AssistantInventoryService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * W-67 slice 1 (D27, HO-2026-10-16 step 1): read-only, non-secret inventory
 * for the Ask entity resolver. Gated in WebConfig (a signed-in session is
 * required, so the household role is resolved), DENY for demo tokens in
 * DemoAccessPolicy, and filtered by role in {@link AssistantInventoryService}.
 */
@RestController
@RequestMapping("/api/assistant")
@CrossOrigin
public class AssistantInventoryController {

    private final AssistantInventoryService service;

    public AssistantInventoryController(AssistantInventoryService service) {
        this.service = service;
    }

    @GetMapping("/inventory")
    public ResponseEntity<?> inventory(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (!AssistantInventoryService.mayRead(role)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "The assistant inventory needs an administrator or adult household member."));
        }
        return ResponseEntity.ok(service.snapshot(role));
    }
}
