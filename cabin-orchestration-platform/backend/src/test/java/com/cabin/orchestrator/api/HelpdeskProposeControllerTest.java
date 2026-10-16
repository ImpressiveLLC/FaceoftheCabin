package com.cabin.orchestrator.api;

import com.cabin.orchestrator.assistant.IntentProposalService;
import com.cabin.orchestrator.assistant.ProposalAuditLog;
import com.cabin.orchestrator.assistant.ProposalModel.Response;
import com.cabin.orchestrator.assistant.ProposalModel.Status;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Wiring only: the flag, the administrator gate, input limits and the principal. The flow is IntentProposalServiceTest. */
class HelpdeskProposeControllerTest {

    private final IntentProposalService service = mock(IntentProposalService.class);
    private final HelpdeskProposeController controller = new HelpdeskProposeController(service, 50);

    private static MockHttpServletRequest request(HouseholdRole role, String email) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/helpdesk/propose");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        if (email != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, email);
        return request;
    }

    @Test
    void whenTheFlagIsOffTheRouteDoesNotExistForAnyone() {
        when(service.enabled()).thenReturn(false);

        for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.ADMINISTRATOR, HouseholdRole.ADULT_HOUSEHOLD_MEMBER, null}) {
            assertEquals(404, controller.propose(Map.of("text", "turn on the heater"), request(role, "a@example.test")).getStatusCode().value());
        }
        verify(service, never()).propose(any(), any(), any());
    }

    @Test
    void onlyAnAdministratorMayPropose() {
        when(service.enabled()).thenReturn(true);

        for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD,
                HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null}) {
            assertEquals(403, controller.propose(Map.of("text", "turn on the heater"), request(role, "a@example.test")).getStatusCode().value(), "role " + role);
        }
        verify(service, never()).propose(any(), any(), any());
    }

    @Test
    void blankMissingOrOversizedTextIsABadRequest() {
        when(service.enabled()).thenReturn(true);
        MockHttpServletRequest admin = request(HouseholdRole.ADMINISTRATOR, "a@example.test");

        assertEquals(400, controller.propose(Map.of(), admin).getStatusCode().value());
        assertEquals(400, controller.propose(Map.of("text", "   "), admin).getStatusCode().value());
        assertEquals(400, controller.propose(Map.of("text", "x".repeat(51)), admin).getStatusCode().value());
        assertEquals(400, controller.propose(null, admin).getStatusCode().value());
        verify(service, never()).propose(any(), any(), any());
    }

    @Test
    void anAdministratorGetsTheServiceResponseWithTheirOwnIdentity() {
        when(service.enabled()).thenReturn(true);
        Response expected = new Response("req-1", Status.CLARIFY, "clarify", null, "Which room?", null, List.of(), List.of(), null);
        when(service.propose("turn it on", HouseholdRole.ADMINISTRATOR, "admin@example.test")).thenReturn(expected);

        ResponseEntity<?> response = controller.propose(Map.of("text", "  turn it on  "), request(HouseholdRole.ADMINISTRATOR, "admin@example.test"));

        assertEquals(200, response.getStatusCode().value());
        assertSame(expected, response.getBody());
    }

    @Test
    void redactionDropsSecretsAndControlCharactersButKeepsPlainRequests() {
        assertEquals("turn on the heater", ProposalAuditLog.redact("turn on the heater"));
        assertFalse(ProposalAuditLog.redact("the api key is sk-1234567890").contains("sk-1234567890"));
        assertFalse(ProposalAuditLog.redact("password=hunter2").contains("hunter2"));
        assertFalse(ProposalAuditLog.redact("AKIAIOSFODNN7EXAMPLEAKIAIOSFODNN7").contains("AKIAIOSFODNN7"));
        assertEquals("line one line two", ProposalAuditLog.redact("line one\u0007line two"));
        assertNull(ProposalAuditLog.redact(null));
    }
}
