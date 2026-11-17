package com.cabin.orchestrator.alerts;

import com.cabin.orchestrator.api.AlertController;
import com.cabin.orchestrator.automation.AutomationRuleService;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertAcknowledgmentPolicyTest {
    private final Instant now = Instant.parse("2026-11-17T06:00:00Z");
    private final ActiveAlertService active = mock(ActiveAlertService.class);
    private final CabinEventService events = mock(CabinEventService.class);
    private final JdbcAlertAcknowledgmentStore store = mock(JdbcAlertAcknowledgmentStore.class);
    private final AlertHistoryService history = mock(AlertHistoryService.class);
    private final AlertAcknowledgmentPolicy policy = new AlertAcknowledgmentPolicy(active, events);
    private final AlertController controller = new AlertController(active, mock(AutomationRuleService.class), store, history, policy);
    private static final String KEY = "device:sensor:alarm";

    @BeforeEach void setup() {
        when(events.statusCheckSeverities(any())).thenReturn(Map.of());
        severity("WARN");
    }

    private void severity(String level) {
        when(active.snapshot()).thenReturn(new ActiveAlertsSnapshot(now, List.of(
            new ActiveAlert(KEY, "sensor", "Sensor", "cabin", "ALARM", level, now, "Alarm", "Detail")), Map.of()));
    }

    record LegacyRequest(String alertKey, String mode, Instant snoozedUntil) {}

    @Test void bootJacksonAcceptsNewUiFieldAgainstLegacyRequestShape() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class))
            .run(context -> {
                var mapper = context.getBean(com.fasterxml.jackson.databind.ObjectMapper.class);
                var request = mapper.readValue("{\"alertKey\":\"key\",\"mode\":\"IGNORED\",\"expectedSeverity\":\"WARN\"}", LegacyRequest.class);
                assertEquals("key", request.alertKey());
                assertFalse(mapper.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
            });
    }

    @Test void criticalLimitIncludesExactlyOneHourButNeverIgnore() {
        assertNull(policy.rejection("CRITICAL", "SNOOZED", now.plusSeconds(3600), now));
        assertNotNull(policy.rejection("CRITICAL", "SNOOZED", now.plusSeconds(3601), now));
        assertNotNull(policy.rejection("critical", "IGNORED", null, now));
        assertNotNull(policy.rejection("CRITICAL", "SNOOZED", null, now));
    }

    @Test void warnDurationsAndIgnoreArePreservedButPastSnoozesAreRejected() {
        assertNull(policy.rejection("WARN", "IGNORED", null, now));
        for (int days : new int[]{1, 7, 30})
            assertNull(policy.rejection("WARN", "SNOOZED", now.plus(Duration.ofDays(days)), now));
        assertNotNull(policy.rejection("WARN", "SNOOZED", now, now));
        assertNotNull(policy.rejection("WARN", "SNOOZED", now.minusSeconds(1), now));
    }

    @Test void warnAndLegacyAcknowledgmentsCannotHideCriticalEscalation() {
        for (String level : new String[]{null, "WARN"}) {
            var snooze = new AlertAcknowledgment(KEY, "SNOOZED", now.plusSeconds(1800), "user", now, level);
            var ignore = new AlertAcknowledgment(KEY, "IGNORED", null, "user", now, level);
            assertTrue(policy.effective(snooze, "WARN", now));
            assertTrue(policy.effective(ignore, "WARN", now));
            assertFalse(policy.effective(snooze, "CRITICAL", now));
            assertFalse(policy.effective(ignore, "CRITICAL", now));
        }
    }

    @Test void onlyUnexpiredBoundedExplicitCriticalSnoozeIsEffective() {
        var ack = new AlertAcknowledgment(KEY, "SNOOZED", now.plusSeconds(3600), "user", now, "CRITICAL");
        assertTrue(policy.effective(ack, "CRITICAL", now));
        assertFalse(policy.effective(ack, "CRITICAL", now.plusSeconds(3600)));
        assertFalse(policy.effective(ack, "CRITICAL", now.minusSeconds(1)));
        assertFalse(policy.effective(new AlertAcknowledgment(KEY, "SNOOZED", now.plusSeconds(3601), "user", now, "CRITICAL"), "CRITICAL", now));
        assertFalse(policy.effective(new AlertAcknowledgment(KEY, "IGNORED", null, "user", now, "CRITICAL"), "CRITICAL", now));
    }

    @Test void missingOrUnknownSeverityCannotCrashOrAuthorizeLongSuppression() {
        var levels = new java.util.HashMap<String,String>();
        levels.put("automation:broken:none", null);
        levels.put("automation:unknown:none", "ERROR");
        when(events.statusCheckSeverities(any())).thenReturn(levels);
        severity(null);
        var resolved = policy.currentSeverities(now);
        assertEquals("CRITICAL", resolved.get(KEY));
        assertEquals("CRITICAL", resolved.get("automation:broken:none"));
        assertEquals("CRITICAL", resolved.get("automation:unknown:none"));
        assertNotNull(policy.rejection(resolved.get(KEY), "IGNORED", null, now));
        verify(events).statusCheckSeverities(now.minus(Duration.ofHours(24)));
    }

    @Test void staleWarnClientCannotAcknowledgeNowCriticalAlert() {
        severity("CRITICAL");
        var response = controller.acknowledge(new AcknowledgeAlertRequest(KEY, "SNOOZED", Instant.now().plusSeconds(120), "WARN"), new MockHttpServletRequest());
        assertEquals(409, response.getStatusCode().value());
        verifyNoInteractions(store, history);
    }

    @Test void serverSeverityIsStoredInsteadOfClientClaim() {
        controller.acknowledge(new AcknowledgeAlertRequest(KEY, "SNOOZED", Instant.now().plusSeconds(120), "CRITICAL"), new MockHttpServletRequest());
        var capture = ArgumentCaptor.forClass(AlertAcknowledgment.class);
        verify(store).save(capture.capture());
        assertEquals("WARN", capture.getValue().acknowledgedSeverity());
        severity("CRITICAL");
        when(store.activeAcknowledgments()).thenReturn(List.of(capture.getValue()));
        assertTrue(controller.acknowledgments().isEmpty());
    }

    @Test void controllerRejectsCriticalIgnoreAndLongSnoozeWithoutWriting() {
        severity("CRITICAL");
        for (var request : List.of(new AcknowledgeAlertRequest(KEY, "IGNORED", null, "CRITICAL"),
            new AcknowledgeAlertRequest(KEY, "SNOOZED", Instant.now().plusSeconds(7200), "CRITICAL")))
            assertEquals(400, controller.acknowledge(request, new MockHttpServletRequest()).getStatusCode().value());
        verifyNoInteractions(store, history);
    }

    @Test void explicitCriticalSnoozeRoundTripsThroughController() {
        severity("CRITICAL");
        var response = controller.acknowledge(new AcknowledgeAlertRequest(KEY, "SNOOZED", Instant.now().plusSeconds(3590), "CRITICAL"), new MockHttpServletRequest());
        assertEquals(200, response.getStatusCode().value());
        var capture = ArgumentCaptor.forClass(AlertAcknowledgment.class);
        verify(store).save(capture.capture());
        assertEquals("CRITICAL", capture.getValue().acknowledgedSeverity());
        when(store.activeAcknowledgments()).thenReturn(List.of(capture.getValue()));
        assertEquals(List.of(capture.getValue()), controller.acknowledgments());
    }
}
