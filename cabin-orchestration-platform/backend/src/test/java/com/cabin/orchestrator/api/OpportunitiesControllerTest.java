package com.cabin.orchestrator.api;

import com.cabin.orchestrator.opportunities.JdbcOptimizationOpportunityStore;
import com.cabin.orchestrator.opportunities.OpportunityStatus;
import com.cabin.orchestrator.opportunities.OpportunityType;
import com.cabin.orchestrator.opportunities.OptimizationOpportunity;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same requestWithRole(...) pattern as PlatformImportControllerTest --
 * real store against Testcontainers Postgres (this controller's own
 * logic, not just role gating, is worth covering against a real backend).
 */
@Testcontainers
class OpportunitiesControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcOptimizationOpportunityStore store;
    private OpportunitiesController controller;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS optimization_opportunity");
        store = new JdbcOptimizationOpportunityStore(jdbc, new ObjectMapper().findAndRegisterModules());
        controller = new OpportunitiesController(store);
    }

    private static MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    private OptimizationOpportunity seed(String id, OpportunityStatus status) {
        OptimizationOpportunity op = new OptimizationOpportunity(id, OpportunityType.POWER_DRAW_ANOMALY,
            "z2m-heater_mech_room", Instant.now(), Map.of("continuousHours", 60), status, null);
        store.save(op);
        return op;
    }

    @Test
    void listIsDeniedForAnAdultHouseholdMemberNotJustChild() {
        ResponseEntity<?> result = controller.list(null, 50, 0, requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void listIsDeniedWithNoRoleAttributeAtAll() {
        ResponseEntity<?> result = controller.list(null, 50, 0, requestWithRole(null));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void administratorSeesTheFullList() {
        seed("op-1", OpportunityStatus.OPEN);
        seed("op-2", OpportunityStatus.RESOLVED);

        ResponseEntity<?> result = controller.list(null, 50, 0, requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        List<OptimizationOpportunity> body = (List<OptimizationOpportunity>) result.getBody();
        assertThat(body).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void statusFilterOnlyReturnsMatchingRows() {
        seed("op-1", OpportunityStatus.OPEN);
        seed("op-2", OpportunityStatus.RESOLVED);

        ResponseEntity<?> result = controller.list("open", 50, 0, requestWithRole(HouseholdRole.ADMINISTRATOR));

        List<OptimizationOpportunity> body = (List<OptimizationOpportunity>) result.getBody();
        assertThat(body).extracting(OptimizationOpportunity::id).containsExactly("op-1");
    }

    @Test
    void anUnknownStatusValueReturnsABadRequestNotA500() {
        ResponseEntity<?> result = controller.list("not-a-real-status", 50, 0, requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void limitAndOffsetSlicePaginateTheSortedList() {
        seed("op-1", OpportunityStatus.OPEN);
        seed("op-2", OpportunityStatus.OPEN);
        seed("op-3", OpportunityStatus.OPEN);

        ResponseEntity<?> result = controller.list(null, 1, 1, requestWithRole(HouseholdRole.ADMINISTRATOR));

        List<OptimizationOpportunity> body = (List<OptimizationOpportunity>) result.getBody();
        assertThat(body).hasSize(1);
    }

    @Test
    void updateStatusIsDeniedForNonAdministrators() {
        seed("op-1", OpportunityStatus.OPEN);

        ResponseEntity<?> result = controller.updateStatus("op-1", Map.of("status", "ACKNOWLEDGED"), requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertEquals(HttpStatus.FORBIDDEN, result.getStatusCode());
    }

    @Test
    void acknowledgingSetsStatusButNotResolvedAt() {
        seed("op-1", OpportunityStatus.OPEN);

        ResponseEntity<?> result = controller.updateStatus("op-1", Map.of("status", "acknowledged"), requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.OK, result.getStatusCode());
        OptimizationOpportunity updated = store.findById("op-1").orElseThrow();
        assertThat(updated.status()).isEqualTo(OpportunityStatus.ACKNOWLEDGED);
        assertThat(updated.resolvedAt()).isNull();
    }

    @Test
    void resolvingSetsBothStatusAndResolvedAt() {
        seed("op-1", OpportunityStatus.OPEN);

        controller.updateStatus("op-1", Map.of("status", "RESOLVED"), requestWithRole(HouseholdRole.ADMINISTRATOR));

        OptimizationOpportunity updated = store.findById("op-1").orElseThrow();
        assertThat(updated.status()).isEqualTo(OpportunityStatus.RESOLVED);
        assertThat(updated.resolvedAt()).isNotNull();
    }

    @Test
    void cannotSetStatusBackToOpenDirectly() {
        seed("op-1", OpportunityStatus.ACKNOWLEDGED);

        ResponseEntity<?> result = controller.updateStatus("op-1", Map.of("status", "OPEN"), requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
        assertThat(store.findById("op-1").orElseThrow().status()).isEqualTo(OpportunityStatus.ACKNOWLEDGED);
    }

    @Test
    void updateStatusOnAnUnknownIdReturns404() {
        ResponseEntity<?> result = controller.updateStatus("does-not-exist", Map.of("status", "RESOLVED"), requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.NOT_FOUND, result.getStatusCode());
    }

    @Test
    void updateStatusWithAnInvalidValueReturnsBadRequestNotA500() {
        seed("op-1", OpportunityStatus.OPEN);

        ResponseEntity<?> result = controller.updateStatus("op-1", Map.of("status", "banana"), requestWithRole(HouseholdRole.ADMINISTRATOR));

        assertEquals(HttpStatus.BAD_REQUEST, result.getStatusCode());
    }
}
