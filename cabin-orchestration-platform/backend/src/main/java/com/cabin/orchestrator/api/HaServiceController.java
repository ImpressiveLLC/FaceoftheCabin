package com.cabin.orchestrator.api;

import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * POST /api/ha/services -- calls a Home Assistant service on behalf of the
 * dashboard (W-27 switch toggles, W-28 "On the Way" preheat). Added
 * 2026-10-02.
 *
 * Deliberately NOT a general HA proxy. This is reachable from the public
 * cabin UI, and an open "call any HA service" endpoint would let an
 * anonymous caller unlock doors or open the main water valve. Two gates:
 *
 *  1. Authenticated: /api/ha/** is in WebConfig's GoogleAuthInterceptor
 *     path list (a signed-in household member, not a kiosk viewer).
 *     Unlike /api/devices reads (D14), there's no glanceable-status case
 *     for anonymous writes.
 *  2. Allowlisted: on/off-style services on switch and input_boolean only,
 *     and never an entity whose id names a valve or lock -- the Zigbee
 *     main water valve shows up in HA as a switch, and leak automations
 *     own it (closing it mid-leak from the dashboard is fine through HA
 *     directly; a stray tap reopening it is not).
 *
 * Widen ALLOWED_DOMAINS/SERVICES only with the same explicit conversation.
 */
@RestController
@RequestMapping("/api/ha")
@CrossOrigin
public class HaServiceController {

    static final Set<String> ALLOWED_DOMAINS = Set.of("switch", "input_boolean");
    static final Set<String> ALLOWED_SERVICES = Set.of("turn_on", "turn_off", "toggle");
    private static final Pattern ENTITY_ID = Pattern.compile("^[a-z0-9_]+\\.[a-z0-9_]+$");
    private static final Pattern DENIED_ENTITY = Pattern.compile("valve|lock");

    private final HomeAssistantAdapter ha;

    public HaServiceController(HomeAssistantAdapter ha) {
        this.ha = ha;
    }

    public record ServiceCall(String domain, String service, String entity_id, String location) {}

    @PostMapping("/services")
    public ResponseEntity<Map<String, Object>> call(@RequestBody ServiceCall req) {
        String rejection = rejectionReason(req);
        if (rejection != null) {
            return ResponseEntity.badRequest().body(Map.of("accepted", false, "error", rejection));
        }
        // The M920q is the head for both locations (docs/MAINTENANCE.md,
        // Home Location, 2026-10-01) -- "cabin" unless a caller says otherwise.
        String location = req.location() == null || req.location().isBlank() ? "cabin" : req.location();
        boolean ok = ha.callService(location, req.domain(), req.service(), req.entity_id(), Map.of());
        Map<String, Object> body = Map.of(
            "accepted", ok,
            "domain", req.domain(),
            "service", req.service(),
            "entity_id", req.entity_id());
        return ok ? ResponseEntity.ok(body) : ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    /** Null when the call is allowed; otherwise a short reason for the 400. */
    static String rejectionReason(ServiceCall req) {
        if (req == null || req.domain() == null || req.service() == null || req.entity_id() == null) {
            return "domain, service and entity_id are required";
        }
        if (!ALLOWED_DOMAINS.contains(req.domain())) {
            return "domain not allowed: " + req.domain();
        }
        if (!ALLOWED_SERVICES.contains(req.service())) {
            return "service not allowed: " + req.service();
        }
        if (!ENTITY_ID.matcher(req.entity_id()).matches()) {
            return "malformed entity_id";
        }
        if (!req.entity_id().startsWith(req.domain() + ".")) {
            return "entity_id must be in the " + req.domain() + " domain";
        }
        if (DENIED_ENTITY.matcher(req.entity_id()).find()) {
            return "entity not allowed from the dashboard: " + req.entity_id();
        }
        if (req.location() != null && !req.location().isBlank()
                && !Set.of("cabin", "home").contains(req.location())) {
            return "unknown location: " + req.location();
        }
        return null;
    }
}
