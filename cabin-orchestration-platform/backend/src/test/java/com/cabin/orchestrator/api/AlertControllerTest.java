package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.AcknowledgeAlertRequest;
import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.alerts.AlertAcknowledgment;
import com.cabin.orchestrator.alerts.JdbcAlertAcknowledgmentStore;
import com.cabin.orchestrator.automation.AutomationRuleService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Same no-Spring-context Testcontainers pattern as RulesControllerTest --
 * the controller is instantiated directly against a real
 * JdbcAlertAcknowledgmentStore; ActiveAlertService/AutomationRuleService
 * are mocked since these tests don't exercise active()/rules(), which
 * already have their own coverage elsewhere.
 */
@Testcontainers
class AlertControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private AlertController controller;
    private JdbcAlertAcknowledgmentStore store;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        store = new JdbcAlertAcknowledgmentStore(jdbc);
        jdbc.execute("TRUNCATE TABLE alert_acknowledgment");
        controller = new AlertController(mock(ActiveAlertService.class), mock(AutomationRuleService.class), store);
    }

    private MockHttpServletRequest authedRequest(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, email);
        return request;
    }

    @Test
    void ignoringAnAlertPersistsItWithNoExpiry() {
        ResponseEntity<?> response = controller.acknowledge(
            new AcknowledgeAlertRequest("device:leak_mech_room:missed-checkin", "IGNORED", null),
            authedRequest("nate@example.com"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        List<AlertAcknowledgment> active = controller.acknowledgments();
        assertEquals(1, active.size());
        assertEquals("IGNORED", active.get(0).mode());
        assertNull(active.get(0).snoozedUntil());
        assertEquals("nate@example.com", active.get(0).acknowledgedBy());
    }

    @Test
    void snoozingRequiresAFutureSnoozedUntil() {
        Instant until = Instant.now().plus(1, ChronoUnit.HOURS);
        ResponseEntity<?> response = controller.acknowledge(
            new AcknowledgeAlertRequest("automation:FREEZE_RISK:zigbee_temp_outside", "SNOOZED", until),
            authedRequest("nate@example.com"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, controller.acknowledgments().size());
    }

    @Test
    void snoozeWithoutSnoozedUntilIsRejected() {
        ResponseEntity<?> response = controller.acknowledge(
            new AcknowledgeAlertRequest("device:leak_mech_room:missed-checkin", "SNOOZED", null),
            authedRequest("nate@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(controller.acknowledgments().isEmpty());
    }

    @Test
    void ignoreWithASnoozedUntilIsRejected() {
        ResponseEntity<?> response = controller.acknowledge(
            new AcknowledgeAlertRequest("device:leak_mech_room:missed-checkin", "IGNORED", Instant.now().plusSeconds(60)),
            authedRequest("nate@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void unknownModeIsRejected() {
        ResponseEntity<?> response = controller.acknowledge(
            new AcknowledgeAlertRequest("device:leak_mech_room:missed-checkin", "SNOOOZE_TYPO", null),
            authedRequest("nate@example.com"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void anExpiredSnoozeStopsBeingActiveAndIsCleanedUp() {
        // Written directly (not through the endpoint, which enforces "future
        // only") to simulate a snooze whose window has genuinely elapsed.
        store.save(new AlertAcknowledgment("device:leak_mech_room:missed-checkin", "SNOOZED",
            Instant.now().minusSeconds(1), "nate@example.com", Instant.now().minusSeconds(3600)));

        assertTrue(controller.acknowledgments().isEmpty(), "an expired snooze must not still suppress the alert");
    }

    @Test
    void clearingAnAcknowledgmentBringsTheAlertBackImmediately() {
        controller.acknowledge(new AcknowledgeAlertRequest("device:leak_mech_room:missed-checkin", "IGNORED", null),
            authedRequest("nate@example.com"));
        assertEquals(1, controller.acknowledgments().size());

        ResponseEntity<?> response = controller.clearAcknowledgment("device:leak_mech_room:missed-checkin");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(controller.acknowledgments().isEmpty());
    }

    @Test
    void reAcknowledgingTheSameKeyUpdatesRatherThanDuplicates() {
        controller.acknowledge(new AcknowledgeAlertRequest("device:leak_mech_room:missed-checkin", "IGNORED", null),
            authedRequest("nate@example.com"));
        Instant until = Instant.now().plus(1, ChronoUnit.DAYS);
        controller.acknowledge(new AcknowledgeAlertRequest("device:leak_mech_room:missed-checkin", "SNOOZED", until),
            authedRequest("nate@example.com"));

        List<AlertAcknowledgment> active = controller.acknowledgments();
        assertEquals(1, active.size(), "one alert key must never produce two rows");
        assertEquals("SNOOZED", active.get(0).mode());
    }
}
