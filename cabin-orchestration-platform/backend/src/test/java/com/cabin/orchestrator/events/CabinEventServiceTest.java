package com.cabin.orchestrator.events;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same Testcontainers-against-real-Postgres pattern as
 * EventPipelineIntegrationTest, but targets CabinEventService.recent()
 * directly (no Kafka round-trip) -- this is specifically about the
 * eventTypePrefix filter and offset pagination added 2026-08-07
 * (Phase 7 §4a/§4c real server-side fix for what CameraEventsPanel was
 * doing client-side). See docs/ontology.yaml's cabin_camera_event entry.
 */
@Testcontainers
class CabinEventServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private CabinEventService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        service = new CabinEventService(jdbc); // constructor does CREATE TABLE IF NOT EXISTS

        // Seed a realistic mix: camera events (DETECTION_*/MOTION_*) interleaved
        // with non-camera device events -- the exact scenario that was the
        // reported bug ("seeing device inputs, not camera events").
        save(service, "evt-1", "front_door", "DETECTION_NEW", "INFO", Instant.now().minusSeconds(10));
        save(service, "evt-2", "leak_mech_room", "STATE_CHANGE", "WARN", Instant.now().minusSeconds(9));
        save(service, "evt-3", "front_door", "MOTION_ON", "INFO", Instant.now().minusSeconds(8));
        save(service, "evt-4", "temp_kitchen", "STATE_CHANGE", "INFO", Instant.now().minusSeconds(7));
        save(service, "evt-5", "driveway", "DETECTION_UPDATE", "INFO", Instant.now().minusSeconds(6));
    }

    private void save(CabinEventService svc, String id, String device, String type, String severity, Instant ts) {
        svc.save(new CabinEvent(id, device, type, severity, ts, Map.of()));
    }

    @Test
    void withNoFilterReturnsEverything() {
        List<CabinEvent> all = service.recent(null, 20, 0, Instant.now().minusSeconds(60), null);
        assertThat(all).hasSize(5);
    }

    @Test
    void eventTypePrefixFilterReturnsOnlyMatchingCameraEvents() {
        List<CabinEvent> cameraOnly = service.recent(
            null, 20, 0, Instant.now().minusSeconds(60), List.of("DETECTION_", "MOTION_"));

        assertThat(cameraOnly).hasSize(3);
        assertThat(cameraOnly).extracting(CabinEvent::eventId)
            .containsExactlyInAnyOrder("evt-1", "evt-3", "evt-5");
        assertThat(cameraOnly).extracting(CabinEvent::eventType)
            .allMatch(t -> t.startsWith("DETECTION_") || t.startsWith("MOTION_"));
    }

    @Test
    void eventTypePrefixFilterExcludesNonCameraDeviceEvents() {
        List<CabinEvent> cameraOnly = service.recent(
            null, 20, 0, Instant.now().minusSeconds(60), List.of("DETECTION_", "MOTION_"));

        assertThat(cameraOnly).extracting(CabinEvent::eventId)
            .doesNotContain("evt-2", "evt-4"); // the STATE_CHANGE (non-camera) events
    }

    @Test
    void offsetPagesPastTheFirstPage() {
        List<CabinEvent> page1 = service.recent(null, 2, 0, Instant.now().minusSeconds(60), null);
        List<CabinEvent> page2 = service.recent(null, 2, 2, Instant.now().minusSeconds(60), null);

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        // Newest-first ordering -- page1 and page2 must not overlap.
        assertThat(page1).extracting(CabinEvent::eventId)
            .doesNotContainAnyElementsOf(page2.stream().map(CabinEvent::eventId).toList());
    }

    @Test
    void combinesDeviceCameraFilterAndPaginationTogether() {
        List<CabinEvent> firstCameraEvent = service.recent(
            "front_door", 1, 0, Instant.now().minusSeconds(60), List.of("DETECTION_", "MOTION_"));

        assertThat(firstCameraEvent).hasSize(1);
        assertThat(firstCameraEvent.get(0).sourceDeviceId()).isEqualTo("front_door");
    }

    @Test
    void threeArgOverloadStillWorksUnfiltered() {
        // The pre-existing recent(deviceId, limit, since) signature -- kept as
        // an overload for backward compatibility with any other caller.
        List<CabinEvent> all = service.recent(null, 20, Instant.now().minusSeconds(60));
        assertThat(all).hasSize(5);
    }
}
