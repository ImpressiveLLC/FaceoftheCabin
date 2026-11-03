package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Admin CRUD (create/list/deactivate/reactivate, invite by email, a sign-in link to copy), each route refusing anyone who is not an ADMINISTRATOR
 * (AdministratorOnly), plus the one
 * genuinely public endpoint in this class -- POST .../magic/{token}/consume,
 * which a managed user's browser calls right after clicking their sign-in
 * link (emailed, or copied by an administrator and handed over). That one can never require a Google token (a managed user has no
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

    private static final Logger log = LoggerFactory.getLogger(ManagedUsersController.class);

    private final ManagedUserService service;

    public ManagedUsersController(ManagedUserService service) {
        this.service = service;
    }

    @GetMapping
    public List<ManagedUser> list(HttpServletRequest request) {
        AdministratorOnly.require(request);
        return service.list();
    }

    @PostMapping
    public ManagedUser create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        AdministratorOnly.require(request);
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
    public Map<String, Object> deactivate(@PathVariable String id, HttpServletRequest request) {
        AdministratorOnly.require(request);
        return setActive(id, false);
    }

    @PostMapping("/{id}/reactivate")
    public Map<String, Object> reactivate(@PathVariable String id, HttpServletRequest request) {
        AdministratorOnly.require(request);
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
    public Map<String, Object> invite(@PathVariable String id, HttpServletRequest request) {
        AdministratorOnly.require(request);
        try {
            service.invite(id);
            return Map.of("sent", true);
        } catch (NoSuchElementException | IllegalStateException e) {
            return Map.of("error", e.getMessage());
        } catch (RestClientException e) {
            // the email service refused or could not be reached (a test sender may only mail its owner): not a server fault, and its reply
            // names the recipient, so only the kind of failure is logged and a way round it is offered
            log.warn("Invite email for managed user {} was not sent ({})", id, e.getClass().getSimpleName());
            return Map.of("error", "The email could not be sent. Use Copy sign-in link and hand the link over instead.");
        }
    }

    /**
     * A sign-in link to copy and give to the person, with no email involved (Nate 2026-11-01: as for guest links). Administrator only;
     * the link is a credential (single use, 30 minutes), so the response is never cached.
     */
    @PostMapping("/{id}/sign-in-link")
    public org.springframework.http.ResponseEntity<Map<String, Object>> signInLink(@PathVariable String id, HttpServletRequest request) {
        AdministratorOnly.require(request);
        org.springframework.http.ResponseEntity.BodyBuilder ok = org.springframework.http.ResponseEntity.ok().header("Cache-Control", "no-store");
        try {
            return ok.body(Map.of("url", service.createSignInLink(id), "expiresInMinutes", 30));
        } catch (NoSuchElementException | IllegalStateException e) {
            return ok.body(Map.of("error", e.getMessage()));
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
            "expiresAt", session.expiresAt().toString(),
            "email", user.email(),
            "name", user.name(),
            "role", user.role().name());
    }
}
