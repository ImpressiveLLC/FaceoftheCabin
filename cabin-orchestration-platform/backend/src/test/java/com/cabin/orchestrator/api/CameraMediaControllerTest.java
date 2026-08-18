package com.cabin.orchestrator.api;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.integrations.cameras.BlinkLiveviewService;
import com.cabin.orchestrator.mqtt.FrigateEventReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 2026-08-18: covers the "camera activity, no clip" gap found live on the
 * M920q while diagnosing that day's driveway/AldrichFront outage --
 * MOTION_ON/OFF events (raw Blink motion relayed over MQTT, see
 * MqttBridgeService) never go through Frigate's own object detection, so
 * they carry no frigateEventId and the existing /events/{frigateEventId}/
 * clip endpoint has nothing to key off for them. Live inspection confirmed
 * Frigate keeps record.enabled=true and continuously records every camera
 * regardless of detection, and its ad-hoc time-range export endpoint
 * (GET /api/{camera}/start/{epoch}/end/{epoch}/clip.mp4) returned a real
 * 200 video/mp4 for an arbitrary window -- so any event's timestamp is
 * recoverable even with no Frigate "event" behind it. clipByTime() is the
 * fallback that uses this. What's covered here is what's testable without
 * a live Frigate instance: the 404-when-unknown short-circuit (proved by
 * never reaching the mocked collaborators), and the exact time-range path
 * Frigate's export API expects.
 */
@Testcontainers
class CameraMediaControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private CameraMediaController controller;
    private CabinEventService eventService;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        eventService = new CabinEventService(jdbc); // constructor does CREATE TABLE IF NOT EXISTS
        controller = new CameraMediaController(
            mock(FrigateEventReconciliationService.class), mock(BlinkLiveviewService.class), eventService);
    }

    @Test
    void clipByTimeReturns404WhenTheEventDoesNotExist() {
        ResponseEntity<byte[]> response = controller.clipByTime("never-saved");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void clipByTimeLooksUpTheEventBeforeAttemptingAnyFrigateCall() {
        // No real Frigate reachable in this test -- if clipByTime tried to
        // proxy for an id that was never saved, it would either NPE
        // (event.timestamp() on null) or hang on a real HTTP call instead
        // of returning cleanly. Reaching a clean 404 is itself proof the
        // eventService.findById() short-circuit fired first.
        eventService.save(new CabinEvent("evt-real", "driveway", "MOTION_ON",
            "INFO", Instant.now(), Map.of("camera", "driveway")));

        ResponseEntity<byte[]> response = controller.clipByTime("some-other-id-not-evt-real");

        assertThat(response.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void frigateTimeRangePathPadsBeforeAndAfterTheEventTimestamp() {
        CabinEvent motionOn = new CabinEvent("evt-1", "driveway", "MOTION_ON", "INFO",
            Instant.ofEpochSecond(1_000_000), Map.of("camera", "driveway"));

        String path = CameraMediaController.frigateTimeRangeClipPath(motionOn);

        assertThat(path).isEqualTo("/api/driveway/start/999990/end/1000030/clip.mp4");
    }

    @Test
    void frigateTimeRangePathUsesTheEventsOwnCameraName() {
        CabinEvent motionOff = new CabinEvent("evt-2", "home_aldrich_front", "MOTION_OFF", "INFO",
            Instant.ofEpochSecond(2_000_000), Map.of("camera", "home_aldrich_front"));

        String path = CameraMediaController.frigateTimeRangeClipPath(motionOff);

        assertThat(path).startsWith("/api/home_aldrich_front/start/");
    }
}
