package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import com.cabin.orchestrator.storage.StorageTrimService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StorageTrimControllerTest {

    private final StorageTrimService service = mock(StorageTrimService.class);
    private final StorageTrimController controller = new StorageTrimController(service);

    private static MockHttpServletRequest as(HouseholdRole role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (role != null) r.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return r;
    }

    @Test
    void everyOtherRoleIsRefusedOnBothMethodsAndNothingIsRequested() {
        for (HouseholdRole role : new HouseholdRole[] { null, HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD }) {
            assertEquals(HttpStatus.FORBIDDEN, controller.plan(as(role)).getStatusCode());
            assertEquals(HttpStatus.FORBIDDEN, controller.trim(new StorageTrimController.TrimRequest(Map.of("backups-home", 1)), as(role)).getStatusCode());
        }
        verify(service, never()).request(any());
    }

    @Test
    void anAdministratorGetsThePlan() {
        when(service.view()).thenReturn(Map.of("installed", true));
        ResponseEntity<?> r = controller.plan(as(HouseholdRole.ADMINISTRATOR));
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(Map.of("installed", true), r.getBody());
    }

    @Test
    void goReturns202WithTheRequestId() {
        when(service.request(Map.of("backups-home", 2))).thenReturn("req-1");
        ResponseEntity<?> r = controller.trim(new StorageTrimController.TrimRequest(Map.of("backups-home", 2)), as(HouseholdRole.ADMINISTRATOR));
        assertEquals(HttpStatus.ACCEPTED, r.getStatusCode());
        assertEquals("req-1", ((Map<?, ?>) r.getBody()).get("requestId"));
    }

    @Test
    void validationFailuresKeepTheirStatusAndMessage() {
        when(service.request(any())).thenThrow(new StorageTrimService.TrimException(HttpStatus.CONFLICT, "A trim is already waiting"));
        ResponseEntity<?> r = controller.trim(new StorageTrimController.TrimRequest(Map.of("backups-home", 2)), as(HouseholdRole.ADMINISTRATOR));
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("A trim is already waiting", ((Map<?, ?>) r.getBody()).get("error"));
    }
}
