package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import com.cabin.orchestrator.storage.StorageTrimService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * W-62: the in-app Trim control's API. ADMINISTRATOR-only for both methods: it lists backup folders and
 * their sizes and, on POST, asks the host to permanently delete the older ones (the same per-route role
 * pattern OpportunitiesController uses). Deletion is not done here; see StorageTrimService.
 */
@RestController
@RequestMapping("/api/system/storage/trim")
@CrossOrigin
public class StorageTrimController {

    private final StorageTrimService service;

    public StorageTrimController(StorageTrimService service) {
        this.service = service;
    }

    /** GET /api/system/storage/trim -- the plan (folders and sizes), the disk, and the last result. */
    @GetMapping
    public ResponseEntity<?> plan(HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        return ResponseEntity.ok(service.view());
    }

    /** POST /api/system/storage/trim body {"keep": {"backups-home": 3, ...}} -- 202 with the request id. */
    @PostMapping
    public ResponseEntity<?> trim(@RequestBody TrimRequest body, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        try {
            String id = service.request(body == null ? null : body.keep());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("requestId", id);
            out.put("accepted", true);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(out);
        } catch (StorageTrimService.TrimException e) {
            return ResponseEntity.status(e.status()).body(Map.of("accepted", false, "error", e.getMessage()));
        }
    }

    public record TrimRequest(Map<String, Integer> keep) {}

    private ResponseEntity<?> requireAdministrator(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only an administrator can trim storage"));
        }
        return null;
    }
}
