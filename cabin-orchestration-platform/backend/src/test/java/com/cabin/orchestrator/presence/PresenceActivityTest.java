package com.cabin.orchestrator.presence;

import com.cabin.orchestrator.api.PresenceActivityController;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.events.OccupancyEdges;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Postgres. Covers the three DB-touching pieces of presence history:
 * PresenceActivityBackfill (TELEMETRY -&gt; edge events), PresenceActivityService
 * (edge events -&gt; per-day summary) and the controller's role gate.
 */
@Testcontainers
class PresenceActivityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SENSOR = "z2m-motion_entry";
    // 13:00 CDT on Sep 15 2026.
    private static final Instant NOW = Instant.parse("2026-09-15T18:00:00Z");

    private JdbcTemplate jdbc;
    private CabinEventService events;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        events = new CabinEventService(jdbc);   // creates cabin_event
        jdbc.execute("TRUNCATE TABLE cabin_event");
    }

    private void telemetry(String id, String time, String device, String payloadJson) {
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            VALUES (?, ?::timestamptz, ?, 'TELEMETRY', 'INFO', ?::jsonb)""", id, time, device, payloadJson);
    }

    private void edge(String time, boolean activated) {
        Instant at = Instant.parse(time);
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            VALUES (?, ?, ?, ?, 'INFO', '{}'::jsonb)""",
            OccupancyEdges.eventId(SENSOR, at, activated ? OccupancyEdges.ACTIVATED : OccupancyEdges.CLEARED),
            java.sql.Timestamp.from(at), SENSOR, activated ? OccupancyEdges.ACTIVATED : OccupancyEdges.CLEARED);
    }

    private PresenceActivityService service() {
        return new PresenceActivityService(jdbc, new DeviceRegistry(List.of()), "America/Chicago");
    }

    // ---- backfill ----

    @Test
    void backfillDerivesTransitionsFromTelemetryAndSkipsRepeats() {
        telemetry("t0", "2026-09-14T14:00:00Z", SENSOR, "{\"occupancy\": false, \"battery\": 100}");
        telemetry("t1", "2026-09-14T14:05:00Z", SENSOR, "{\"occupancy\": true}");
        telemetry("t2", "2026-09-14T14:05:30Z", SENSOR, "{\"occupancy\": true, \"battery\": 99}");   // repeat
        telemetry("t3", "2026-09-14T14:06:00Z", SENSOR, "{\"occupancy\": false}");
        telemetry("t4", "2026-09-14T15:00:00Z", SENSOR, "{\"occupancy\": true}");
        telemetry("t5", "2026-09-14T15:00:20Z", SENSOR, "{\"occupancy\": true}");                     // repeat
        telemetry("t6", "2026-09-14T15:01:00Z", SENSOR, "{\"battery\": 98}");                         // no occupancy at all
        telemetry("o0", "2026-09-14T15:00:00Z", "z2m-door_front_contact", "{\"contact\": true}");      // other device

        int written = new PresenceActivityBackfill(jdbc).run();

        assertEquals(3, written, "ACTIVATED, CLEARED, ACTIVATED");
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT event_type, time FROM cabin_event WHERE event_type LIKE 'OCCUPANCY_SENSOR_%' ORDER BY time");
        assertEquals(List.of(OccupancyEdges.ACTIVATED, OccupancyEdges.CLEARED, OccupancyEdges.ACTIVATED),
            rows.stream().map(r -> (String) r.get("event_type")).toList());
    }

    @Test
    void runningTheBackfillAgainAddsNothing() {
        telemetry("t0", "2026-09-14T14:00:00Z", SENSOR, "{\"occupancy\": false}");
        telemetry("t1", "2026-09-14T14:05:00Z", SENSOR, "{\"occupancy\": true}");
        PresenceActivityBackfill backfill = new PresenceActivityBackfill(jdbc);

        assertEquals(1, backfill.run());
        assertEquals(0, backfill.run(), "deterministic ids + ON CONFLICT DO NOTHING");
    }

    @Test
    void theBackfilledIdMatchesWhatTheLivePathWouldHaveWrittenForTheSameInstant() {
        // Sub-millisecond precision on purpose: the live id truncates to millis, so the SQL must too.
        telemetry("t0", "2026-09-14T14:00:00.500000Z", SENSOR, "{\"occupancy\": false}");
        telemetry("t1", "2026-09-14T14:05:00.999999Z", SENSOR, "{\"occupancy\": true}");

        new PresenceActivityBackfill(jdbc).run();

        String expected = OccupancyEdges.eventId(SENSOR, Instant.parse("2026-09-14T14:05:00.999999Z"), OccupancyEdges.ACTIVATED);
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_id = ?", Integer.class, expected));
    }

    @Test
    void aTransitionTheLivePathAlreadyRecordedIsNotDuplicated() {
        Instant at = Instant.parse("2026-09-14T14:05:00.250Z");
        telemetry("t0", "2026-09-14T14:00:00Z", SENSOR, "{\"occupancy\": false}");
        telemetry("t1", "2026-09-14T14:05:00.250Z", SENSOR, "{\"occupancy\": true}");
        events.save(OccupancyEdges.edge(SENSOR, false, true, at, "cabin").orElseThrow());

        assertEquals(0, new PresenceActivityBackfill(jdbc).run());
        assertEquals(1, jdbc.queryForObject(
            "SELECT count(*) FROM cabin_event WHERE event_type = ?", Integer.class, OccupancyEdges.ACTIVATED));
    }

    // ---- service ----

    @Test
    void theServiceSummarisesEdgesPerLocalDayWithZeroDaysFilledIn() {
        // Sep 14 local: two activations 5 min apart (one visit, 3 min occupied), Sep 15 local: one.
        edge("2026-09-14T14:00:00Z", true);  edge("2026-09-14T14:01:00Z", false);
        edge("2026-09-14T14:05:00Z", true);  edge("2026-09-14T14:07:00Z", false);
        edge("2026-09-15T15:00:00Z", true);  edge("2026-09-15T15:01:00Z", false);

        Map<String, Object> body = service().activity(null, 7, 30, NOW);

        assertEquals("America/Chicago", body.get("timezone"));
        assertEquals(7, body.get("days"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sensors = (List<Map<String, Object>>) body.get("sensors");
        assertEquals(1, sensors.size());
        Map<String, Object> s = sensors.get(0);
        assertEquals(SENSOR, s.get("deviceId"));
        assertEquals(SENSOR, s.get("name"), "falls back to the id when the registry doesn't know the device");
        assertEquals(0, s.get("daysSinceLastActivity"));

        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertEquals(3, totals.get("activations"));
        assertEquals(2, totals.get("visits"));
        assertEquals(2, totals.get("activeDays"));
        assertEquals(4.0, (Double) totals.get("activeMinutes"), 0.001);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> byDay = (List<Map<String, Object>>) s.get("byDay");
        assertEquals(7, byDay.size());
        assertEquals("2026-09-09", byDay.get(0).get("date"));
        assertEquals(0, byDay.get(0).get("activations"));
        assertEquals("2026-09-14", byDay.get(5).get("date"));
        assertEquals(2, byDay.get(5).get("activations"));
        assertEquals(1, byDay.get(5).get("visits"));
        assertEquals(1, byDay.get(6).get("activations"));
    }

    @Test
    void daysSinceLastActivityLooksBeyondTheWindow() {
        edge("2026-08-01T14:00:00Z", true);
        edge("2026-08-01T14:01:00Z", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> s = ((List<Map<String, Object>>) service().activity(null, 7, 30, NOW).get("sensors")).get(0);

        assertEquals(45, s.get("daysSinceLastActivity"), "Aug 1 -> Sep 15, though the 7-day window is empty");
        @SuppressWarnings("unchecked")
        Map<String, Object> totals = (Map<String, Object>) s.get("totals");
        assertEquals(0, totals.get("activations"));
    }

    @Test
    void theServiceCanBeScopedToOneSensor() {
        edge("2026-09-14T14:00:00Z", true);
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            VALUES ('x1', '2026-09-14T14:00:00Z', 'z2m-motion_other', ?, 'INFO', '{}'::jsonb)""", OccupancyEdges.ACTIVATED);

        assertEquals(2, ((List<?>) service().activity(null, 7, 30, NOW).get("sensors")).size());
        assertEquals(1, ((List<?>) service().activity(SENSOR, 7, 30, NOW).get("sensors")).size());
    }

    @Test
    void outOfRangeParametersAreClamped() {
        Map<String, Object> body = service().activity(null, 100000, 0, NOW);

        assertEquals(365, body.get("days"));
        assertEquals(1, body.get("visitGapMinutes"));
    }

    // ---- controller role gate (D14) ----

    private static MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/presence/activity");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    @Test
    void onlyAdministratorsAndAdultsMayReadPresenceHistory() {
        PresenceActivityController controller = new PresenceActivityController(service());

        for (HouseholdRole denied : new HouseholdRole[] { HouseholdRole.CHILD, HouseholdRole.KIOSK_DISPLAY, null }) {
            ResponseEntity<?> r = controller.activity(requestWithRole(denied), null, null, null);
            assertEquals(HttpStatus.FORBIDDEN, r.getStatusCode(), "role " + denied);
        }
        for (HouseholdRole allowed : new HouseholdRole[] { HouseholdRole.ADMINISTRATOR, HouseholdRole.ADULT_HOUSEHOLD_MEMBER }) {
            ResponseEntity<?> r = controller.activity(requestWithRole(allowed), null, null, null);
            assertEquals(HttpStatus.OK, r.getStatusCode(), "role " + allowed);
        }
    }
}
