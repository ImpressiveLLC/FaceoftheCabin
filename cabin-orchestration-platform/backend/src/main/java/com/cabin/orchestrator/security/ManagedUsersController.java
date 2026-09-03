package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Admin CRUD (create/list/deactivate/reactivate/invite) plus the one
 * genuinely public endpoint in this class -- POST .../magic/{token}/consume,
 * which a managed user's browser calls right after clicking their emailed
 * link. That one can never require a Google token (a managed user has no
 * Google account by definition), so it gets its own exact carve-out in
 * GoogleAuthInterceptor.preHandle() -- see that method's own comment --
 * the same technique already used for the tech-id findings collection.
 * Every other endpoint here stays behind the normal Google gate via
 * WebConfig's /api/managed-users/** pattern.
 */
@RestController
@RequestMapping("/api/managed-users")
@CrossOrigin
public class ManagedUsersController {

    private final ManagedUserService service;

    public ManagedUsersController(ManagedUserService service) {
        this.service = service;
    }

    @GetMapping
    public List<ManagedUser> list() {
        return service.list();
    }

    @PostMapping
    public ManagedUser create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String email = body.get("email");
        String name = body.get("name");
        String roleRaw = body.get("role");
        if (email == null || email.isBlank()) throw new IllegalArgumentException("email is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        ManagedUserRole role;
        try {
            role = ManagedUserRole.valueOf(roleRaw);
        } catch (Exception e) {
            throw new IllegalArgumentException("role must be VIEWER or HOUSEHOLD_MEMBER");
        }
        String createdBy = (String) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        return service.create(email, name, role, createdBy);
    }

    @PostMapping("/{id}/deactivate")
    public Map<String, Object> deactivate(@PathVariable String id) {
        return setActive(id, false);
    }

    @PostMapping("/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable String id) {
        return setActive(id, true);
    }

    private Map<String, Object> setActive(String id, boolean active) {
        try {
            service.setActive(id, active);
            return Map.of("id", id, "active", active);
        } catch (NoSuchElementException e) {
            return Map.of("error", e.getMessage());
        }
    }

    @PostMapping("/{id}/invite")
    public Map<String, Object> invite(@PathVariable String id) {
        try {
            service.invite(id);
            return Map.of("sent", true);
        } catch (NoSuchElementException | IllegalStateException e) {
            return Map.of("error", e.getMessage());
        }
    }

    /** Public -- see class doc. Exchanges a clicked magic link for a real session token the browser stores and sends back as `Authorization: ManagedSession {token}`. */
    @PostMapping("/magic/{token}/consume")
    public Map<String, Object> consumeMagicLink(@PathVariable String token) {
        Optional<ManagedUserSession> sessionOpt = service.consumeMagicLink(token);
        if (sessionOpt.isEmpty()) {
            return Map.of("error", "This link is invalid, expired, already used, or the account is no longer active");
        }
        ManagedUserSession session = sessionOpt.get();
        ManagedUser user = service.validateSession(session.token()).orElseThrow();
        return Map.of(
            "sessionToken", session.token(),
            "email", user.email(),
            "name", user.name(),
            "role", user.role().name());
    }
}
