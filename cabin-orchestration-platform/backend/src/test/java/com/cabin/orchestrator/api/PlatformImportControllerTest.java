package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceLifecycleRecord;
import com.cabin.orchestrator.devices.DeviceLifecycleStore;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.platformimport.ImportUpsertOutcome;
import com.cabin.orchestrator.platformimport.PlatformImportProvider;
import com.cabin.orchestrator.platformimport.PlatformImportRecord;
import com.cabin.orchestrator.platformimport.PlatformImportRecordRepository;
import com.cabin.orchestrator.platformimport.PlatformImportTranslationService;
import com.cabin.orchestrator.platformimport.RawImportRecord;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WSJF #9 success criterion 6: ADMINISTRATOR required on every import
 * endpoint, one denial test per adapter (SmartThings and Ring), 403 for
 * lower roles. Mirrors CrossDomainControllerTest's own pattern for the
 * same reason: no live CHILD/ADULT_HOUSEHOLD_MEMBER auth path is needed to
 * prove the per-route policy check itself works.
 */
class PlatformImportControllerTest {

    private FakeRecordRepository recordRepository;

    private PlatformImportController newController() {
        PlatformImportProvider smartThings = fakeProvider("smartthings");
        PlatformImportProvider ring = fakeProvider("ring");
        recordRepository = new FakeRecordRepository();
        return new PlatformImportController(List.of(smartThings, ring),
            new PlatformImportTranslationService(), recordRepository, new DeviceRegistry(List.of()));
    }

    private static PlatformImportProvider fakeProvider(String platform) {
        return new PlatformImportProvider() {
            @Override public String platform() { return platform; }
            @Override public List<RawImportRecord> listDevices() {
                return List.of(new RawImportRecord(platform, "1", "Test Device", "loc", List.of("temperature"), java.util.Map.of()));
            }
        };
    }

    private static MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/platform-import");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    @Test
    void aChildRolePrincipalIsDeniedSmartThingsProposals() {
        ResponseEntity<?> result = newController().proposals("smartthings", requestWithRole(HouseholdRole.CHILD));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void aChildRolePrincipalIsDeniedRingProposals() {
        ResponseEntity<?> result = newController().proposals("ring", requestWithRole(HouseholdRole.CHILD));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void anAdultHouseholdMemberIsDeniedProposalsAdminOnly() {
        ResponseEntity<?> result = newController().proposals("smartthings", requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void noRoleAtAllIsDeniedNotTreatedAsPrivileged() {
        ResponseEntity<?> result = newController().proposals("smartthings", requestWithRole(null));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void anAdministratorGetsRealProposalsForSmartThings() {
        ResponseEntity<?> result = newController().proposals("smartthings", requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void anAdministratorGetsRealProposalsForRing() {
        ResponseEntity<?> result = newController().proposals("ring", requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void confirmIsAdministratorOnlyToo() {
        ResponseEntity<?> result = newController().confirm("smartthings", Map.of(), requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void confirmRejectsAMissingRequiredField() {
        PlatformImportController controller = newController();
        recordRepository.seed("smartthings", "1");

        ResponseEntity<?> result = controller.confirm("smartthings",
            confirmBody("1", "smartthings-kitchen", "Kitchen Temp", "TEMPERATURE_SENSOR", null),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    void confirmRejectsAnInvalidLocation() {
        PlatformImportController controller = newController();
        recordRepository.seed("smartthings", "1");

        ResponseEntity<?> result = controller.confirm("smartthings",
            confirmBody("1", "smartthings-kitchen", "Kitchen Temp", "TEMPERATURE_SENSOR", "garage"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    void confirmRejectsAnUnknownDeviceType() {
        PlatformImportController controller = newController();
        recordRepository.seed("smartthings", "1");

        ResponseEntity<?> result = controller.confirm("smartthings",
            confirmBody("1", "smartthings-kitchen", "Kitchen Temp", "NOT_A_REAL_TYPE", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    void confirmReturns404WhenNoPendingImportMatches() {
        PlatformImportController controller = newController();
        // Deliberately not seeded -- no proposals() call ever happened for this originalId.

        ResponseEntity<?> result = controller.confirm("smartthings",
            confirmBody("does-not-exist", "smartthings-kitchen", "Kitchen Temp", "TEMPERATURE_SENSOR", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }

    @Test
    void confirmCreatesADeviceAtCandidateAndMarksTheImportRecordConfirmed() {
        PlatformImportController controller = newController();
        recordRepository.seed("smartthings", "1");

        ResponseEntity<?> result = controller.confirm("smartthings",
            confirmBody("1", "smartthings-kitchen_temp", "Kitchen Temp", "TEMPERATURE_SENSOR", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("CANDIDATE", ((Map<?, ?>) result.getBody()).get("deviceLifecycle"),
            "must not auto-promote -- DeviceLifecycleState has no ACTIVE value, see this controller's own javadoc");
        assertEquals("smartthings-kitchen_temp", recordRepository.find("smartthings", "1").orElseThrow().confirmedEntityId());
    }

    @Test
    void confirmIsRejectedOnASecondAttemptForTheSameImport() {
        PlatformImportController controller = newController();
        recordRepository.seed("smartthings", "1");
        controller.confirm("smartthings", confirmBody("1", "smartthings-kitchen_temp", "Kitchen Temp", "TEMPERATURE_SENSOR", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        ResponseEntity<?> result = controller.confirm("smartthings",
            confirmBody("1", "smartthings-kitchen_temp_2", "Kitchen Temp", "TEMPERATURE_SENSOR", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.CONFLICT, result.getStatusCode(), "D10: never overwrite a confirmed entity_id on re-import");
    }

    @Test
    void confirmIsRejectedWhenTheEntityIdIsAlreadyInUse() {
        PlatformImportController controller = newController();
        recordRepository.seed("smartthings", "1");
        recordRepository.seed("smartthings", "2");
        controller.confirm("smartthings", confirmBody("1", "smartthings-kitchen_temp", "Kitchen Temp", "TEMPERATURE_SENSOR", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        ResponseEntity<?> result = controller.confirm("smartthings",
            confirmBody("2", "smartthings-kitchen_temp", "A Different Device", "TEMPERATURE_SENSOR", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.CONFLICT, result.getStatusCode());
    }

    /**
     * D10 restart-durability fix (2026-09-08): confirm() must persist the
     * CANDIDATE it creates -- registerCandidate()'s in-memory-only design is
     * correct for Z2M/HA's ongoing rediscovery loop, but a platform import
     * has no such loop, so a device confirmed here previously vanished on
     * the next cabin-backend restart before anyone got to Accept it.
     */
    @Test
    void confirmedCandidateSurvivesARestartWithItsProvenanceTagIntact() {
        RecordingLifecycleStore store = new RecordingLifecycleStore();
        DeviceRegistry registry = new DeviceRegistry(List.of(), store);
        PlatformImportController controller = new PlatformImportController(
            List.of(fakeProvider("smartthings"), fakeProvider("ring")),
            new PlatformImportTranslationService(), recordRepository = new FakeRecordRepository(), registry);
        recordRepository.seed("smartthings", "1");

        controller.confirm("smartthings",
            confirmBody("1", "smartthings-kitchen_temp", "Kitchen Temp", "TEMPERATURE_SENSOR", "cabin"),
            requestWithRole(HouseholdRole.ADMINISTRATOR));

        DeviceRegistry restarted = new DeviceRegistry(List.of(), store);
        assertEquals(DeviceLifecycleState.CANDIDATE, restarted.lifecycleState("smartthings-kitchen_temp"));
        assertEquals("smartthings", restarted.get("smartthings-kitchen_temp").attributes().get("importedFrom"),
            "D10 provenance tag must be durable, not just the ephemeral runtime attribute it used to be");
    }

    /** In-memory stand-in for JdbcDeviceLifecycleStore, shared across two DeviceRegistry instances to simulate a restart. */
    private static final class RecordingLifecycleStore implements DeviceLifecycleStore {
        private final Map<String, DeviceLifecycleRecord> records = new HashMap<>();

        @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.copyOf(records); }
        @Override public void save(DeviceLifecycleRecord record) { records.put(record.descriptor().deviceId(), record); }
        @Override public void delete(String deviceId) { records.remove(deviceId); }
    }

    private static Map<String, Object> confirmBody(String originalId, String entityId, String name, String type, String location) {
        Map<String, Object> body = new HashMap<>();
        body.put("originalId", originalId);
        body.put("entityId", entityId);
        body.put("name", name);
        body.put("type", type);
        body.put("location", location);
        return body;
    }

    @Test
    void anAdultHouseholdMemberCanReadRecordsButAChildCannot() {
        PlatformImportController controller = newController();

        ResponseEntity<?> asAdult = controller.records(requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));
        ResponseEntity<?> asChild = controller.records(requestWithRole(HouseholdRole.CHILD));

        assertEquals(HttpStatus.OK, asAdult.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, asChild.getStatusCode());
    }

    /** In-memory, keyed by "platform/originalId" -- matches the real repository's (platform, originalId) PRIMARY KEY. */
    private static final class FakeRecordRepository implements PlatformImportRecordRepository {
        private final Map<String, PlatformImportRecord> records = new HashMap<>();

        void seed(String platform, String originalId) {
            records.put(key(platform, originalId), new PlatformImportRecord(
                platform, originalId, "Test Device", "loc", "{}", null, Instant.now(), Instant.now()));
        }

        @Override public ImportUpsertOutcome upsert(RawImportRecord raw) { return ImportUpsertOutcome.NEW; }
        @Override public List<PlatformImportRecord> loadAll() { return List.copyOf(records.values()); }
        @Override public List<PlatformImportRecord> findByPlatform(String platform) { return List.copyOf(records.values()); }
        @Override public Optional<PlatformImportRecord> find(String platform, String originalId) {
            return Optional.ofNullable(records.get(key(platform, originalId)));
        }
        @Override public boolean markConfirmed(String platform, String originalId, String confirmedEntityId) {
            PlatformImportRecord existing = records.get(key(platform, originalId));
            if (existing == null || existing.confirmedEntityId() != null) return false;
            records.put(key(platform, originalId), new PlatformImportRecord(
                existing.platform(), existing.originalId(), existing.originalName(), existing.originalLocation(),
                existing.rawPayloadJson(), confirmedEntityId, existing.importedAt(), Instant.now()));
            return true;
        }
        private static String key(String platform, String originalId) { return platform + "/" + originalId; }
    }
}
