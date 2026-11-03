package com.cabin.orchestrator.api;

import com.cabin.orchestrator.recovery.RecoveryHeartbeatService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecoveryControllerTest {

    private final RecoveryHeartbeatService service = mock(RecoveryHeartbeatService.class);
    private final RecoveryController controller = new RecoveryController(service);

    private static MockHttpServletRequest as(HouseholdRole role) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (role != null) r.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return r;
    }

    @Test
    void everyOtherRoleIsRefusedAndNothingIsRead() {
        for (HouseholdRole role : new HouseholdRole[] { null, HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD }) {
            assertEquals(HttpStatus.FORBIDDEN, controller.view(as(role)).getStatusCode());
        }
        verify(service, never()).view(any());
    }

    @Test
    void anAdministratorGetsTheView() {
        when(service.view(any(Instant.class))).thenReturn(Map.of("state", "ok"));
        ResponseEntity<?> r = controller.view(as(HouseholdRole.ADMINISTRATOR));
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals(Map.of("state", "ok"), r.getBody());
    }
}
