package com.cabin.orchestrator.api;

import com.cabin.orchestrator.platformimport.ImportProposal;
import com.cabin.orchestrator.platformimport.ImportUpsertOutcome;
import com.cabin.orchestrator.platformimport.PlatformImportProvider;
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
import java.util.stream.Collectors;

/**
 * WSJF #9 -- backend half of the platform import pipeline (D10). The
 * frontend confirmation screen is explicitly a separate, later task
 * (Cowork's Settings panel sprint) -- confirm() below is deliberately a
 * stub, not wired to DeviceRegistry yet (see its own javadoc). Role gates
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

    public PlatformImportController(List<PlatformImportProvider> providers,
                                     PlatformImportTranslationService translationService,
                                     PlatformImportRecordRepository recordRepository) {
        this.providersByPlatform = providers.stream().collect(Collectors.toMap(PlatformImportProvider::platform, p -> p));
        this.translationService = translationService;
        this.recordRepository = recordRepository;
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
     * Deliberately stubbed per this item's own scope note ("wire the backend
     * endpoint that returns the translation proposal; stub the confirmation
     * POST"): the real DeviceRegistry wiring (entity_id persisted, provenance
     * tag imported:platform_name) is a separate follow-up alongside the
     * frontend confirmation screen.
     */
    @PostMapping("/{platform}/confirm")
    public ResponseEntity<?> confirm(@PathVariable String platform, @RequestBody Map<String, Object> body, HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
            "status", "stubbed",
            "message", "Device creation from a platform import is not yet wired to DeviceRegistry -- "
                + "tracked as a separate follow-up alongside the frontend confirmation screen."));
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
