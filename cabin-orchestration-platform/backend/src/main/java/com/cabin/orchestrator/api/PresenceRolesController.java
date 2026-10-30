package com.cabin.orchestrator.api;

import com.cabin.orchestrator.presence.LocationRole;
import com.cabin.orchestrator.presence.LocationRoleService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.presence.TrackedPersonStore;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * W-38 slice 1 (D24): who is primary and who is maintenance at each location. ADMINISTRATOR-only for both methods, the same
 * per-route pattern StorageTrimController uses; the whole /api/presence prefix is already gated and denied to demo links.
 * Roles never grant or remove a permission (R-LR-3).
 */
@RestController
@RequestMapping("/api/presence/roles")
@CrossOrigin
public class PresenceRolesController {

    private final LocationRoleService roles;
    private final PresenceSignalRegistry signals;

    public PresenceRolesController(LocationRoleService roles, PresenceSignalRegistry signals) {
        this.roles = roles;
        this.signals = signals;
    }

    /** The assignments, plus who is currently being counted without one (the "unregistered, counted" flag). */
    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignments", roles.assignments().stream().map(PresenceRolesController::view).toList());
        body.put("unregisteredCounted", signals.unregisteredCounted().stream()
            .map(s -> Map.of("location", s.location(), "personId", s.personId())).toList());
        return ResponseEntity.ok(body);
    }

    /** Body {"personId": "nate", "location": "cabin", "roles": ["primary"]}; an empty list removes the assignment. */
    @PutMapping
    public ResponseEntity<?> assign(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        try {
            Set<LocationRole> parsed = EnumSet.noneOf(LocationRole.class);
            Object raw = body.get("roles");
            if (raw instanceof List<?> list) {
                for (Object item : list) parsed.add(LocationRole.valueOf(String.valueOf(item).trim().toUpperCase()));
            } else if (raw != null) {
                throw new IllegalArgumentException("roles must be a list of primary and maintenance");
            }
            roles.assign((String) body.get("personId"), (String) body.get("location"), parsed);
            return ResponseEntity.ok(Map.of("assignments", roles.assignments().stream().map(PresenceRolesController::view).toList()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static Map<String, Object> view(TrackedPersonStore.Assignment a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personId", a.personId());
        out.put("location", a.location());
        out.put("roles", a.roles().stream().map(r -> r.name().toLowerCase()).toList());
        return out;
    }

    private ResponseEntity<?> requireAdministrator(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "Only an administrator can assign location roles"));
        }
        return null;
    }
}
