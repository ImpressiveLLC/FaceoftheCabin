package com.cabin.orchestrator.api;

import com.cabin.orchestrator.locations.HubLocation;
import com.cabin.orchestrator.locations.HubLocationService;
import com.cabin.orchestrator.presence.LocationRole;
import com.cabin.orchestrator.presence.LocationRoleService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.presence.TrackedPersonStore;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** W-38 slice 1: the roles API is administrator only and answers 400, not 500, for bad input. */
class PresenceRolesControllerTest {

    static class MemoryStore implements TrackedPersonStore {
        final Map<String, Set<LocationRole>> rows = new HashMap<>();
        public List<Assignment> findAll() {
            List<Assignment> out = new ArrayList<>();
            rows.forEach((k, v) -> { String[] p = k.split("@"); out.add(new Assignment(p[0], p[1], v, null)); });
            return out;
        }
        public Set<LocationRole> rolesOf(String personId, String location) { return rows.getOrDefault(personId + "@" + location, Set.of()); }
        public void setRoles(String personId, String location, Set<LocationRole> roles) {
            if (roles.isEmpty()) rows.remove(personId + "@" + location); else rows.put(personId + "@" + location, EnumSet.copyOf(roles));
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final HubLocationService locations = mock(HubLocationService.class);
    private final PresenceRolesController controller;

    PresenceRolesControllerTest() {
        when(locations.byId(anyString())).thenReturn(null);
        when(locations.byId("cabin")).thenReturn(mock(HubLocation.class));
        controller = new PresenceRolesController(new LocationRoleService(store), new PresenceSignalRegistry(), locations);
    }

    private static MockHttpServletRequest as(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    @Test
    void onlyAnAdministratorMayReadOrWrite() {
        Map<String, Object> body = Map.of("personId", "nate", "location", "cabin", "roles", List.of("primary"));
        for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD, HouseholdRole.SERVICE}) {
            assertThat(controller.list(as(role)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(controller.assign(body, as(role)).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(controller.list(new MockHttpServletRequest()).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(store.rows).isEmpty();
    }

    @Test
    void anAdministratorAssignsAndListsRoles() {
        ResponseEntity<?> put = controller.assign(Map.of("personId", "nate", "location", "cabin", "roles", List.of("primary", "maintenance")), as(HouseholdRole.ADMINISTRATOR));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(store.rows.get("nate@cabin")).containsExactlyInAnyOrder(LocationRole.PRIMARY, LocationRole.MAINTENANCE);
        assertThat(controller.list(as(HouseholdRole.ADMINISTRATOR)).getStatusCode()).isEqualTo(HttpStatus.OK);

        controller.assign(Map.of("personId", "nate", "location", "cabin", "roles", List.of()), as(HouseholdRole.ADMINISTRATOR));
        assertThat(store.rows).isEmpty();
    }

    @Test
    void badInputIs400NotAServerError() {
        MockHttpServletRequest admin = as(HouseholdRole.ADMINISTRATOR);
        assertThat(controller.assign(Map.of("personId", 42, "location", "cabin", "roles", List.of("primary")), admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.assign(Map.of("personId", "nate", "location", "mars", "roles", List.of("primary")), admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.assign(Map.of("personId", "nate", "location", "cabin", "roles", List.of("owner")), admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.assign(Map.of("personId", "nate", "location", "cabin", "roles", "primary"), admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.assign(Map.of("location", "cabin"), admin).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(store.rows).isEmpty();
    }
}
