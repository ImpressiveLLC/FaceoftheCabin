package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.AcknowledgeAlertRequest;
import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.alerts.AlertAcknowledgment;
import com.cabin.orchestrator.alerts.AlertHistoryEntry;
import com.cabin.orchestrator.alerts.AlertHistoryService;
import com.cabin.orchestrator.alerts.JdbcAlertHistoryStore;
import com.cabin.orchestrator.events.CabinEventService;
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
    private JdbcAlertHistoryStore historyStore;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        historyStore = new JdbcAlertHistoryStore(jdbc);
        store = new JdbcAlertAcknowledgmentStore(jdbc, historyStore);
        jdbc.execute("TRUNCATE TABLE alert_acknowledgment");
        jdbc.execute("TRUNCATE TABLE alert_history");
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        jdbc.execute("DROP TABLE IF EXISTS device_reporting_relationship");
        CabinEventService events = new CabinEventService(jdbc);
        controller = new AlertController(mock(ActiveAlertService.class), mock(AutomationRuleService.class), store,
            new AlertHistoryService(historyStore, events));
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

        ResponseEntity<?> response = controller.clearAcknowledgment("device:leak_mech_room:missed-checkin", authedRequest("nate@example.com"));

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

    private List<AlertHistoryEntry> history() {
        return controller.history("7d", 100, 0, null);
    }

    @Test
    void ignoreSnoozeAndUnIgnoreAreAllKeptInTheHistory() {
        String key = "device:leak_mech_room:missed-checkin";
        controller.acknowledge(new AcknowledgeAlertRequest(key, "IGNORED", null), authedRequest("nate@example.com"));
        controller.acknowledge(new AcknowledgeAlertRequest(key, "SNOOZED", Instant.now().plus(1, ChronoUnit.HOURS)),
            authedRequest("nate@example.com"));
        controller.clearAcknowledgment(key, authedRequest("nate@example.com"));

        List<String> actions = history().stream().map(AlertHistoryEntry::action).toList();
        assertEquals(List.of("UN_IGNORED", "SNOOZED", "IGNORED"), actions, "newest first, and nothing lost to the upsert");
        AlertHistoryEntry first = history().get(2);
        assertEquals("device", first.kind());
        assertEquals("leak_mech_room", first.deviceId());
        assertEquals("MISSED_CHECKIN", first.condition());
        assertEquals("nate@example.com", first.actor());
    }

    @Test
    void aRejectedAcknowledgmentLeavesNoHistory() {
        controller.acknowledge(new AcknowledgeAlertRequest("device:x:alarm", "SNOOZED", null), authedRequest("nate@example.com"));
        assertTrue(history().isEmpty());
    }

    @Test
    void anExpiredSnoozeIsLoggedOnceAtTheTimeItExpired() {
        Instant expiredAt = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        store.save(new AlertAcknowledgment("device:leak_mech_room:missed-checkin", "SNOOZED",
            expiredAt, "nate@example.com", Instant.now().minusSeconds(3600)));

        controller.acknowledgments();
        controller.acknowledgments();

        List<AlertHistoryEntry> expiries = history().stream().filter(e -> "SNOOZE_EXPIRED".equals(e.action())).toList();
        assertEquals(1, expiries.size(), "a second read must not log the same expiry again");
        assertEquals(expiredAt, expiries.get(0).occurredAt());
        assertNull(expiries.get(0).actor());
    }

    @Test
    void automationAlertsAlreadyInTheEventLogAppearAsRaisedWithoutBeingCopied() {
        CabinEventService events = new CabinEventService(new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
        events.save(new com.cabin.orchestrator.events.CabinEvent("e1", "z2m-temp_outside_lowest", "AUTOMATION_ALERT",
            "WARN", Instant.now().minusSeconds(30),
            java.util.Map.of("ruleId", "FREEZE_RISK", "see", "Outdoor temperature is near freezing")));
        events.save(new com.cabin.orchestrator.events.CabinEvent("e2", "z2m-temp_outside_lowest", "TELEMETRY",
            "INFO", Instant.now().minusSeconds(20), java.util.Map.of("temperature", 1.0)));
        controller.acknowledge(new AcknowledgeAlertRequest("automation:FREEZE_RISK:z2m-temp_outside_lowest", "IGNORED", null),
            authedRequest("nate@example.com"));

        List<AlertHistoryEntry> entries = history();
        assertEquals(2, entries.size(), "telemetry is not an alert");
        assertEquals("IGNORED", entries.get(0).action());
        AlertHistoryEntry raised = entries.get(1);
        assertEquals("RAISED", raised.action());
        assertEquals("e:e1", raised.entryId());
        assertEquals("automation:FREEZE_RISK:z2m-temp_outside_lowest", raised.alertKey());
        assertEquals("Outdoor temperature is near freezing", raised.title());
    }

    @Test
    void historyCanBeNarrowedToOneDeviceAndPaged() {
        controller.acknowledge(new AcknowledgeAlertRequest("device:a:alarm", "IGNORED", null), authedRequest("n@example.com"));
        controller.acknowledge(new AcknowledgeAlertRequest("device:b:alarm", "IGNORED", null), authedRequest("n@example.com"));
        controller.acknowledge(new AcknowledgeAlertRequest("device:b:missed-checkin", "IGNORED", null), authedRequest("n@example.com"));

        assertEquals(2, controller.history("7d", 100, 0, "b").size());
        assertEquals(1, controller.history("7d", 1, 1, null).size());
        assertEquals(3, controller.history("garbage", 100, 0, null).size(), "an unparseable window falls back to 7d");
    }
}
