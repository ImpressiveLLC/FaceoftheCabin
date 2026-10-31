package com.cabin.orchestrator.api;

import com.cabin.orchestrator.locations.HubLocationService;
import com.cabin.orchestrator.presence.LocationRole;
import com.cabin.orchestrator.presence.LocationRoleService;
import com.cabin.orchestrator.presence.PresenceService;
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
    private final HubLocationService locations;
    private final PresenceService presenceService;

    public PresenceRolesController(LocationRoleService roles, PresenceSignalRegistry signals, HubLocationService locations,
                                   PresenceService presenceService) {
        this.roles = roles;
        this.signals = signals;
        this.locations = locations;
        this.presenceService = presenceService;
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
            // An absent key is an error, never a removal: only an explicit empty list removes (W-129 item 3).
            if (!body.containsKey("roles") || raw == null) {
                throw new IllegalArgumentException("roles is required: a list of primary and maintenance, or an empty list to remove");
            }
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    try {
                        parsed.add(LocationRole.valueOf(String.valueOf(item).trim().toUpperCase()));
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("unknown role '" + item + "': use primary or maintenance");
                    }
                }
            } else if (raw != null) {
                throw new IllegalArgumentException("roles must be a list of primary and maintenance");
            }
            String location = text(body.get("location"), "location").trim();
            // A role for a place that does not exist would silently never apply.
            if (locations.byId(location) == null) throw new IllegalArgumentException("unknown location '" + location + "'");
            String person = text(body.get("personId"), "personId").trim();
            roles.assign(person, location, parsed);
            // The stored signal was recorded under the old roles: re-judge it now so a departure is not lost and the
            // "unregistered" flag clears at once.
            signals.reevaluate(location, person);
            presenceService.recomputeFromSignals();
            return ResponseEntity.ok(Map.of("assignments", roles.assignments().stream().map(PresenceRolesController::view).toList()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String s) || s.isBlank()) throw new IllegalArgumentException(field + " is required and must be a string");
        return s;
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
