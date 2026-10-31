package com.cabin.orchestrator.alerts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AlertTransitionRecorderTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String KEY = "device:z2m-leak_mech_room:missed-checkin";

    private JdbcAlertHistoryStore store;
    private ActiveAlertService active;
    private AlertTransitionRecorder recorder;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        store = new JdbcAlertHistoryStore(jdbc);
        jdbc.execute("TRUNCATE TABLE alert_history");
        active = mock(ActiveAlertService.class);
        recorder = newRecorder(0);
    }

    private AlertTransitionRecorder newRecorder(long graceMillis) {
        AlertTransitionRecorder r = new AlertTransitionRecorder(active, store);
        ReflectionTestUtils.setField(r, "enabled", true);
        ReflectionTestUtils.setField(r, "startupGraceMillis", graceMillis);
        return r;
    }

    private void currently(ActiveAlert... alerts) {
        when(active.snapshot()).thenReturn(new ActiveAlertsSnapshot(Instant.now(), List.of(alerts), Map.of()));
    }

    private ActiveAlert missedCheckin() {
        return new ActiveAlert(KEY, "z2m-leak_mech_room", "Mech room leak sensor", "cabin",
            "MISSED_CHECKIN", "WARN", Instant.now(), "Mech room leak sensor missed its check-in window", "detail");
    }

    private List<String> actions() {
        return store.since(Instant.now().minusSeconds(3600), null, 100).stream().map(AlertHistoryEntry::action).toList();
    }

    @Test
    void aNewConditionIsLoggedOnceNotOnEveryCycle() {
        currently(missedCheckin());
        recorder.recordTransitions();
        recorder.recordTransitions();
        recorder.recordTransitions();

        assertEquals(List.of("RAISED"), actions());
        AlertHistoryEntry e = store.since(Instant.now().minusSeconds(3600), null, 10).get(0);
        assertEquals("MISSED_CHECKIN", e.condition());
        assertEquals("Mech room leak sensor missed its check-in window", e.title());
    }

    @Test
    void aConditionIsResolvedOnlyAfterItStaysGoneForTwoCycles() {
        currently(missedCheckin());
        recorder.recordTransitions();

        currently();
        recorder.recordTransitions();
        assertEquals(List.of("RAISED"), actions(), "one quiet cycle could be a blip");

        currently(missedCheckin());
        recorder.recordTransitions();
        currently();
        recorder.recordTransitions();
        assertEquals(List.of("RAISED"), actions(), "reappearing resets the count; still one RAISED, no flap logged");

        recorder.recordTransitions();
        assertEquals(List.of("RESOLVED", "RAISED"), actions());
    }

    @Test
    void aRecurrenceAfterResolutionIsAFreshRaise() {
        currently(missedCheckin());
        recorder.recordTransitions();
        currently();
        recorder.recordTransitions();
        recorder.recordTransitions();
        currently(missedCheckin());
        recorder.recordTransitions();

        assertEquals(List.of("RAISED", "RESOLVED", "RAISED"), actions());
    }

    @Test
    void aRestartDoesNotForgetOrDoubleCountAnOpenCondition() {
        currently(missedCheckin());
        recorder.recordTransitions();

        AlertTransitionRecorder afterRestart = newRecorder(0);
        afterRestart.recordTransitions();

        assertEquals(List.of("RAISED"), actions());
    }

    @Test
    void nothingIsResolvedWhileTheRegistryMayStillBeRepopulatingAfterBoot() {
        currently(missedCheckin());
        recorder.recordTransitions();

        AlertTransitionRecorder justBooted = newRecorder(60_000);
        currently(); // adapters haven't reported yet
        justBooted.recordTransitions();
        justBooted.recordTransitions();
        justBooted.recordTransitions();

        assertEquals(List.of("RAISED"), actions());
    }
}
