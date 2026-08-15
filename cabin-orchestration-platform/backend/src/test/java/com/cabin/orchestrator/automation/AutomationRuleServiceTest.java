package com.cabin.orchestrator.automation;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceProfile;
import com.cabin.orchestrator.presence.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Found 2026-08-11 (user report, comparing the real product against
 * impressive.llc's marketing site's water-pressure See/Think/Act scenario):
 * this class existed, had real thresholds, but nothing anywhere called
 * evaluate() and its trigger*() methods were TODO-stubs that only logged --
 * a real, safety-relevant gap, not a missing test. This suite is the first
 * real coverage AutomationRuleService has ever had.
 */
class AutomationRuleServiceTest {

    private PresenceService presenceService;
    private EventPublisher eventPublisher;
    private AutomationRuleService rules;

    @BeforeEach
    void setUp() {
        presenceService = mock(PresenceService.class);
        eventPublisher = mock(EventPublisher.class);
        rules = new AutomationRuleService(presenceService, eventPublisher);
        // @Value fields are only populated by Spring's container -- plain
        // `new` outside a Spring context leaves them at 0.0, not their
        // configured defaults (30/75/38). Same pattern this codebase already
        // uses for EventPublisher/EventConsumer's bootstrapServers in tests.
        ReflectionTestUtils.setField(rules, "lowPsiAlert", 30.0);
        ReflectionTestUtils.setField(rules, "highPsiAlert", 75.0);
        ReflectionTestUtils.setField(rules, "freezeRiskTempF", 38.0);
    }

    private CabinEvent telemetry(Map<String, Object> payload) {
        return new CabinEvent("evt-1", "psi_mech_room", "TELEMETRY", "INFO", java.time.Instant.now(), payload);
    }

    @SuppressWarnings("unchecked")
    private CabinEvent captureOnePublish() {
        ArgumentCaptor<CabinEvent> captor = ArgumentCaptor.forClass(CabinEvent.class);
        verify(eventPublisher).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void waterPressureLowWhileCabinUnoccupiedPublishesCriticalUnexpectedUseAlert() {
        when(presenceService.get()).thenReturn(PresenceProfile.AWAY);

        rules.evaluate(telemetry(Map.of("psi", 26.0)));

        CabinEvent published = captureOnePublish();
        assertEquals("AUTOMATION_ALERT", published.eventType());
        assertEquals("CRITICAL", published.severity());
        assertEquals("psi_mech_room", published.sourceDeviceId());
        assertEquals("Pressure dropped below the safe range.", published.payload().get("see"));
        assertEquals("Alert Nate", published.payload().get("act"));
        assertTrue(((String) published.payload().get("think")).contains("away"));
        assertTrue(((String) published.payload().get("think")).contains("26.0"));
        @SuppressWarnings("unchecked")
        List<String> tags = (List<String>) published.payload().get("tags");
        assertTrue(tags.contains("UNEXPECTED USE"), "Away + low pressure must be tagged unexpected, matching the marketing scenario");
    }

    @Test
    void waterPressureLowWhileCabinOccupiedStaysWarnNotCritical() {
        when(presenceService.get()).thenReturn(PresenceProfile.AT_CABIN);

        rules.evaluate(telemetry(Map.of("psi", 26.0)));

        CabinEvent published = captureOnePublish();
        assertEquals("WARN", published.severity(),
            "someone actually at the cabin is an ordinary explanation for a fixture running -- must not cry wolf");
    }

    @Test
    void waterPressureLowWhileBothOccupiedAlsoStaysWarn() {
        when(presenceService.get()).thenReturn(PresenceProfile.BOTH_OCCUPIED);

        rules.evaluate(telemetry(Map.of("psi", 26.0)));

        assertEquals("WARN", captureOnePublish().severity());
    }

    @Test
    void waterPressureLowWhileAtHomeIsStillUnexpectedForTheCabin() {
        // AT_HOME means someone is at Home, not the cabin -- the cabin
        // itself is still unoccupied, so this must escalate the same as AWAY.
        when(presenceService.get()).thenReturn(PresenceProfile.AT_HOME);

        rules.evaluate(telemetry(Map.of("psi", 26.0)));

        assertEquals("CRITICAL", captureOnePublish().severity());
    }

    @Test
    void normalPressureDoesNotPublishAnything() {
        rules.evaluate(telemetry(Map.of("psi", 55.0)));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void highPressurePublishesWarnNotCritical() {
        rules.evaluate(telemetry(Map.of("psi", 90.0)));

        CabinEvent published = captureOnePublish();
        assertEquals("WARN", published.severity());
        assertEquals("WATER_PRESSURE_HIGH", published.payload().get("ruleId"));
    }

    @Test
    void freezeRiskPublishesCriticalAlert() {
        rules.evaluate(telemetry(Map.of("temp_f", 30.0)));

        CabinEvent published = captureOnePublish();
        assertEquals("CRITICAL", published.severity());
        assertEquals("FREEZE_RISK", published.payload().get("ruleId"));
    }

    @Test
    void safetyAlarmAlwaysPublishesCriticalRegardlessOfPresence() {
        when(presenceService.get()).thenReturn(PresenceProfile.AT_CABIN);

        rules.evaluate(new CabinEvent("evt-2", "smoke_kitchen", "ALARM", "CRITICAL",
            java.time.Instant.now(), Map.of("smoke", true)));

        CabinEvent published = captureOnePublish();
        assertEquals("CRITICAL", published.severity());
        assertEquals("SAFETY_ALARM", published.payload().get("ruleId"));
    }

    @Test
    void lockUnlockedWhileAwayStillPublishesRealAlertNotJustALogLine() {
        // Regression guard for the actual bug found: this specific rule
        // already had presence reasoning before this fix, but its
        // triggerAlert() call was a no-op stub like all the others.
        when(presenceService.get()).thenReturn(PresenceProfile.AWAY);

        rules.evaluate(new CabinEvent("evt-3", "lock_front_door", "STATE_CHANGE", "INFO",
            java.time.Instant.now(), Map.of("deviceType", "LOCK", "state", "unlocked")));

        CabinEvent published = captureOnePublish();
        assertEquals("CRITICAL", published.severity());
        assertEquals("LOCK_UNLOCKED_WHILE_AWAY", published.payload().get("ruleId"));
    }

    @Test
    void lockUnlockedAtHomeIsExpectedAndPublishesNothing() {
        when(presenceService.get()).thenReturn(PresenceProfile.AT_HOME);

        rules.evaluate(new CabinEvent("evt-4", "lock_front_door", "STATE_CHANGE", "INFO",
            java.time.Instant.now(), Map.of("deviceType", "LOCK", "state", "unlocked")));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void unknownEventTypeIsIgnored() {
        rules.evaluate(new CabinEvent("evt-5", "dev-1", "SOME_OTHER_TYPE", "INFO",
            java.time.Instant.now(), Map.of()));

        verifyNoInteractions(eventPublisher);
    }

    @Test
    void ruleCatalogReflectsInjectedThresholdsAndReadOnlyOwnership() {
        List<AutomationRuleStatus> catalog = rules.ruleStatuses();

        AutomationRuleStatus lowPressure = catalog.stream()
            .filter(rule -> rule.ruleId().equals("WATER_PRESSURE_LOW"))
            .findFirst().orElseThrow();
        assertTrue(lowPressure.trigger().contains("30.0"));
        assertEquals("CABIN_BACKEND", lowPressure.owner());
        assertEquals("DEPLOY_TIME", lowPressure.configurationMode());
        assertFalse(lowPressure.editable(), "the UI must not imply it saved a rule configuration it cannot persist");

        AutomationRuleStatus safety = catalog.stream()
            .filter(rule -> rule.ruleId().equals("SAFETY_ALARM"))
            .findFirst().orElseThrow();
        assertEquals("CRITICAL", safety.severity());
        assertTrue(safety.action().contains("only when a channel is configured"));
    }
}
