package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The actual denial-test gate for D11's authorization-model hard gate
 * (WSJF #6): "GET /api/[new-cross-domain-route] with a child-role
 * principal returns 403" running green is what "done" means here. The
 * role is injected directly as a request attribute -- the same pattern
 * every other no-Spring-context controller test in this codebase already
 * uses (see RulesControllerTest's authedRequest()) -- since there is
 * deliberately no live CHILD-role authentication path yet (see
 * HouseholdRole's own doc); this test proves the per-route policy CHECK
 * enforces correctly, independent of which future auth path eventually
 * produces that role.
 */
class CrossDomainControllerTest {

    private CrossDomainController newController() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        DeviceHealthMonitor healthMonitor = new DeviceHealthMonitor(registry, null);
        return new CrossDomainController(new ActiveAlertService(registry, healthMonitor));
    }

    private MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/cross-domain/cabin-status-summary");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    @Test
    void aChildRolePrincipalIsDeniedNotGivenData() {
        CrossDomainController controller = newController();

        ResponseEntity<Map<String, Object>> result = controller.cabinStatusSummary(requestWithRole(HouseholdRole.CHILD));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
        assertFalse(result.getBody().containsKey("criticalAlertCount"), "a denial must never leak the data it's denying");
    }

    @Test
    void aKioskDisplayRolePrincipalIsAlsoDenied() {
        CrossDomainController controller = newController();

        ResponseEntity<Map<String, Object>> result = controller.cabinStatusSummary(requestWithRole(HouseholdRole.KIOSK_DISPLAY));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void aServiceRolePrincipalIsAlsoDenied() {
        CrossDomainController controller = newController();

        ResponseEntity<Map<String, Object>> result = controller.cabinStatusSummary(requestWithRole(HouseholdRole.SERVICE));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void aRequestWithNoRoleAtAllIsDeniedNotTreatedAsPrivileged() {
        // No auth path ever left the attribute unset for a request that
        // reaches this controller at all (WebConfig gates the whole prefix)
        // -- but the check must fail closed if it somehow did, not silently
        // treat "unknown" as "allowed."
        CrossDomainController controller = newController();

        ResponseEntity<Map<String, Object>> result = controller.cabinStatusSummary(requestWithRole(null));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void anAdministratorIsAllowedAndGetsRealData() {
        CrossDomainController controller = newController();

        ResponseEntity<Map<String, Object>> result = controller.cabinStatusSummary(requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertTrue(result.getBody().containsKey("criticalAlertCount"));
        assertTrue(result.getBody().containsKey("generatedAt"));
    }

    @Test
    void anAdultHouseholdMemberIsAllowedAndGetsRealData() {
        CrossDomainController controller = newController();

        ResponseEntity<Map<String, Object>> result = controller.cabinStatusSummary(requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }

    @Test
    void theResponseNeverIncludesRawDeviceDataOnlyCoarseAggregates() {
        // Matches D11's own "minimal aggregate assertion" framing -- pin
        // this exact key set so a future edit can't silently start leaking
        // device ids/names/locations through what's meant to stay coarse.
        CrossDomainController controller = newController();

        ResponseEntity<Map<String, Object>> result = controller.cabinStatusSummary(requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(Set.of("criticalAlertCount", "warnAlertCount", "anyActiveAlert", "generatedAt"), result.getBody().keySet());
    }
}
