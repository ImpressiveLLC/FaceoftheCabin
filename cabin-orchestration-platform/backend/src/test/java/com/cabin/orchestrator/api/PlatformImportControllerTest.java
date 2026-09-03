package com.cabin.orchestrator.api;

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

import java.util.List;
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

    private PlatformImportController newController() {
        PlatformImportProvider smartThings = fakeProvider("smartthings");
        PlatformImportProvider ring = fakeProvider("ring");
        return new PlatformImportController(List.of(smartThings, ring),
            new PlatformImportTranslationService(), new FakeRecordRepository());
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
        ResponseEntity<?> result = newController().confirm("smartthings", java.util.Map.of(), requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void confirmIsDeliberatelyStubbedForAnAdministrator() {
        ResponseEntity<?> result = newController().confirm("smartthings", java.util.Map.of(), requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.NOT_IMPLEMENTED, result.getStatusCode());
    }

    @Test
    void anAdultHouseholdMemberCanReadRecordsButAChildCannot() {
        PlatformImportController controller = newController();

        ResponseEntity<?> asAdult = controller.records(requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));
        ResponseEntity<?> asChild = controller.records(requestWithRole(HouseholdRole.CHILD));

        assertEquals(HttpStatus.OK, asAdult.getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, asChild.getStatusCode());
    }

    private static final class FakeRecordRepository implements PlatformImportRecordRepository {
        @Override public ImportUpsertOutcome upsert(RawImportRecord raw) { return ImportUpsertOutcome.NEW; }
        @Override public List<PlatformImportRecord> loadAll() { return List.of(); }
        @Override public List<PlatformImportRecord> findByPlatform(String platform) { return List.of(); }
        @Override public Optional<PlatformImportRecord> find(String platform, String originalId) { return Optional.empty(); }
    }
}
