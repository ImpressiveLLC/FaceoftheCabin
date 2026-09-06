package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.platformimport.ImportProposal;
import com.cabin.orchestrator.platformimport.ImportUpsertOutcome;
import com.cabin.orchestrator.platformimport.PlatformImportProvider;
import com.cabin.orchestrator.platformimport.PlatformImportRecord;
import com.cabin.orchestrator.platformimport.PlatformImportRecordRepository;
import com.cabin.orchestrator.platformimport.PlatformImportTranslationService;
import com.cabin.orchestrator.platformimport.RawImportRecord;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * WSJF #9 -- backend half of the platform import pipeline (D10). confirm()
 * (Sprint 5 WSJF #3, r7 handover) wires the real DeviceRegistry write this
 * class's own javadoc used to describe as a deliberate stub -- see that
 * method's own comment for the shape and for the real gaps found while
 * building it, flagged rather than silently worked around. Role gates
 * follow the exact per-route pattern CrossDomainController established for
 * WSJF #6 (commit a2e3ef1): read HouseholdRole from the request attribute
 * GoogleAuthInterceptor already set, never re-derive it.
 */
@RestController
@RequestMapping("/api/platform-import")
@CrossOrigin
public class PlatformImportController {

    private final Map<String, PlatformImportProvider> providersByPlatform;
    private final PlatformImportTranslationService translationService;
    private final PlatformImportRecordRepository recordRepository;
    private final DeviceRegistry deviceRegistry;

    public PlatformImportController(List<PlatformImportProvider> providers,
                                     PlatformImportTranslationService translationService,
                                     PlatformImportRecordRepository recordRepository,
                                     DeviceRegistry deviceRegistry) {
        this.providersByPlatform = providers.stream().collect(Collectors.toMap(PlatformImportProvider::platform, p -> p));
        this.translationService = translationService;
        this.recordRepository = recordRepository;
        this.deviceRegistry = deviceRegistry;
    }

    /**
     * GET /api/platform-import/{platform}/proposals -- ADMINISTRATOR only.
     * Fetches the platform's live device list, reconciles each one against
     * the (platform, originalId) dedup gate, and returns a translation
     * proposal for every device -- D10's "user confirmation screen" step,
     * minus the screen itself.
     */
    @GetMapping("/{platform}/proposals")
    public ResponseEntity<?> proposals(@PathVariable String platform, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        PlatformImportProvider provider = providersByPlatform.get(platform);
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown platform: " + platform));
        }
        List<RawImportRecord> raw = provider.listDevices();
        List<Map<String, Object>> results = raw.stream().map(record -> {
            ImportUpsertOutcome outcome = recordRepository.upsert(record);
            ImportProposal proposal = translationService.propose(record);
            return Map.<String, Object>of("proposal", proposal, "upsertOutcome", outcome.name());
        }).toList();
        return ResponseEntity.ok(results);
    }

    /**
     * GET /api/platform-import/records -- ADULT_HOUSEHOLD_MEMBER and above.
     * Read-only: what's already been imported, no proposal generation, no
     * live platform call, never a raw OAuth token.
     */
    @GetMapping("/records")
    public ResponseEntity<?> records(HttpServletRequest request) {
        HouseholdRole role = roleOf(request);
        if (role != HouseholdRole.ADMINISTRATOR && role != HouseholdRole.ADULT_HOUSEHOLD_MEMBER) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This role cannot view import records"));
        }
        return ResponseEntity.ok(recordRepository.loadAll());
    }

    /**
     * POST /api/platform-import/{platform}/confirm -- ADMINISTRATOR only.
     * D10's "user confirmation screen" step, minus the AI part: entityId/
     * name/type/location are the human's own confirmed values (proposals()
     * above already gave them a suggested entityId; this endpoint doesn't
     * recompute or second-guess it -- D10's "user confirms before any
     * entity_id persists" means whatever the caller sends here IS the
     * confirmed value, not a candidate to re-translate).
     *
     * Body: {"originalId", "entityId", "name", "type" (a real DeviceType
     * name), "location" ("cabin" | "home")}. All required -- this endpoint
     * does not guess a DeviceType from measurementTypeCandidates the way a
     * naive auto-mapping might; the r7 handover's "never guess a mapping
     * that isn't in the spec" rule (already load-bearing in
     * SmartThingsImportProvider's own CAPABILITY_TO_MEASUREMENT_TYPE
     * comment) applies here too. The frontend may pre-fill a suggested type
     * from the proposal's measurementTypeCandidates for convenience, but the
     * backend contract stays simple: the admin says what type this is.
     *
     * Creates the device at DeviceLifecycleState.CANDIDATE via
     * registerCandidate() -- deliberately NOT auto-promoted to AVAILABLE/
     * ASSIGNED (there is no "ACTIVE" state in DeviceLifecycleState; the
     * r7 handover's own wording used a name this enum doesn't have -- see
     * this class's Discrepancy Log pin). A separate, later "Confirm" action
     * in Device Manager (the *existing* POST /api/devices/{id}/lifecycle
     * with {"action":"ACCEPT"} -- no new endpoint needed) is what actually
     * accepts it into scope.
     *
     * Real gap found and flagged, not silently worked around:
     * registerCandidate() is designed for devices with an ongoing discovery
     * loop (Z2M/HA poll every restart) that keeps re-registering a
     * CANDIDATE in memory -- "passive discovery deliberately never
     * persists" per that method's own comment. Platform imports have no
     * such loop: a CANDIDATE created here will not survive a cabin-backend
     * restart before someone ACCEPTs it. Fixing that properly means either
     * a scheduled re-sync per platform or letting CANDIDATE rows persist,
     * both real design decisions outside this item's scope -- see the
     * matching Discrepancy Log pin.
     */
    @PostMapping("/{platform}/confirm")
    public ResponseEntity<?> confirm(@PathVariable String platform, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        PlatformImportProvider provider = providersByPlatform.get(platform);
        if (provider == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown platform: " + platform));
        }

        String originalId = stringField(body, "originalId");
        String entityId = stringField(body, "entityId");
        String name = stringField(body, "name");
        String typeName = stringField(body, "type");
        String location = stringField(body, "location");
        if (originalId == null || entityId == null || name == null || typeName == null || location == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "originalId, entityId, name, type, and location are all required"));
        }
        if (!location.equals("cabin") && !location.equals("home")) {
            return ResponseEntity.badRequest().body(Map.of("error", "location must be \"cabin\" or \"home\""));
        }
        DeviceType type;
        try {
            type = DeviceType.valueOf(typeName);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown device type: " + typeName));
        }

        PlatformImportRecord record = recordRepository.find(platform, originalId).orElse(null);
        if (record == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "No pending import found for " + platform + "/" + originalId + " -- fetch proposals first"));
        }
        if (record.confirmedEntityId() != null) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Already confirmed", "entityId", record.confirmedEntityId()));
        }
        if (deviceRegistry.descriptor(entityId).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "entityId \"" + entityId + "\" is already in use by another device"));
        }

        // capabilities is deliberately just TELEMETRY: no protocol adapter is
        // wired for platform-imported devices yet (no live poll/command path
        // -- see PlatformImportProvider's own javadoc, "never writes
        // anything"), so COMMAND/PRESENCE/etc. would misrepresent what this
        // device can actually do through this app today. connectionString is
        // the platform's own originalId, not a real connection string, since
        // there's nothing live to connect to yet -- kept for traceability
        // back to the source platform.
        DeviceDescriptor descriptor = new DeviceDescriptor(
            entityId, name, type, Set.of(DeviceCapability.TELEMETRY),
            "platform_import", originalId, false, location);
        // "vendor" here (not a made-up value) rides DeviceRegistry's own
        // existing vendor/model -> DeviceMetadata upsert path (see
        // registerCandidate()'s own code) -- same mechanism Zigbee2MqttAdapter
        // already uses, not a new one. D10's "provenance tag: imported:
        // platform_name" doesn't map onto a real column on DeviceMetadata
        // today (only D4's createdBy/modifiedBy/version exist, and
        // DeviceRepository.upsert() is itself a no-op until a real `device`
        // row exists -- see this method's own restart-durability note) --
        // "importedFrom" is carried as a plain runtime attribute instead so
        // it's at least visible immediately, not silently dropped.
        deviceRegistry.registerCandidate(descriptor, Map.of(
            "vendor", platformDisplayName(platform),
            "importedFrom", platform));
        recordRepository.markConfirmed(platform, originalId, entityId);

        return ResponseEntity.ok(Map.of(
            "deviceId", entityId, "platform", platform, "originalId", originalId,
            "deviceLifecycle", deviceRegistry.lifecycleState(entityId).name()));
    }

    private static String platformDisplayName(String platform) {
        return switch (platform) {
            case "smartthings" -> "SmartThings";
            case "ring" -> "Ring";
            default -> platform;
        };
    }

    private static String stringField(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private ResponseEntity<?> requireAdministrator(HttpServletRequest request) {
        if (roleOf(request) != HouseholdRole.ADMINISTRATOR) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "This role cannot manage platform imports"));
        }
        return null;
    }

    private static HouseholdRole roleOf(HttpServletRequest request) {
        return (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
    }
}
