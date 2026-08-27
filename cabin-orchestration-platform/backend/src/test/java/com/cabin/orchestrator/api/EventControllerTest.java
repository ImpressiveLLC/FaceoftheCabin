package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-08-15: cabin_event has no location column of its own (see
 * EventController's own comment on why -- events are saved from many call
 * sites, not just camera MQTT). GET /api/events?location= instead joins
 * post-fetch against DeviceRegistry.byLocation(), which is what these
 * tests actually exercise -- added while wiring AldrichFront (a Home
 * camera relayed through the cabin M920q's own blinkbridge/Frigate, with
 * no separate home-location backend for a real per-location query to hit
 * instead).
 */
@Testcontainers
class EventControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private EventController controller;
    private DeviceRegistry registry;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        CabinEventService eventService = new CabinEventService(jdbc);
        registry = new DeviceRegistry(List.of());
        controller = new EventController(eventService, registry);

        registerCamera("driveway", "cabin");
        registerCamera("home_aldrich_front", "home");

        eventService.save(new CabinEvent("evt-driveway", "driveway", "DETECTION_NEW",
            "INFO", Instant.now(), Map.of()));
        eventService.save(new CabinEvent("evt-home", "home_aldrich_front", "DETECTION_NEW",
            "INFO", Instant.now(), Map.of()));
    }

    private void registerCamera(String cameraId, String location) {
        registry.registerCandidate(new DeviceDescriptor(cameraId, cameraId, DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM), "mqtt", "cabin/camera/" + cameraId, false, location),
            Map.of());
        DeviceStatus candidate = registry.get(cameraId);
        registry.update(new DeviceStatus(cameraId, DeviceType.CAMERA, cameraId, "ONLINE",
            Instant.now(), candidate.attributes(), location));
    }

    @Test
    void noLocationFilterReturnsEventsFromEveryLocation() {
        List<CabinEvent> events = controller.recentEvents(null, 20, 0, "24h", null, null);

        assertEquals(2, events.size());
    }

    @Test
    void locationFilterReturnsOnlyThatLocationsEvents() {
        List<CabinEvent> homeEvents = controller.recentEvents(null, 20, 0, "24h", null, "home");

        assertEquals(1, homeEvents.size());
        assertEquals("home_aldrich_front", homeEvents.get(0).sourceDeviceId());
    }

    @Test
    void locationFilterExcludesEventsFromOtherLocations() {
        List<CabinEvent> cabinEvents = controller.recentEvents(null, 20, 0, "24h", null, "cabin");

        assertEquals(1, cabinEvents.size());
        assertEquals("driveway", cabinEvents.get(0).sourceDeviceId());
    }

    @Test
    void locationFilterForAnUnknownDeviceIdReturnsNothing() {
        CabinEventService rawEventService = new CabinEventService(
            new JdbcTemplate(new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
        rawEventService.save(new CabinEvent("evt-orphan", "never-registered", "DETECTION_NEW",
            "INFO", Instant.now(), Map.of()));

        List<CabinEvent> homeEvents = controller.recentEvents(null, 20, 0, "24h", null, "home");

        assertEquals(1, homeEvents.size(),
            "an event from a device never seen by this registry must not silently count toward any location");
    }

    // 2026-08-25: /telemetry-history -- wiring only, dailyAggregates()'s
    // own aggregation logic is covered in CabinEventServiceTest.
    @Test
    void telemetryHistoryDelegatesToDailyAggregates() {
        CabinEventService rawEventService = new CabinEventService(
            new JdbcTemplate(new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
        rawEventService.save(new CabinEvent("evt-humidity", "z2m-humid_mech", "TELEMETRY",
            "INFO", Instant.now(), Map.of("humidity", 75)));

        List<com.cabin.orchestrator.events.TelemetryDailyPoint> points =
            controller.telemetryHistory("z2m-humid_mech", "humidity", 30);

        assertEquals(1, points.size());
        assertEquals(75.0, points.get(0).avg());
    }

    // 2026-08-27: /reported-fields -- wiring only, reportedFieldsByDevice()'s
    // own query logic is covered in CabinEventServiceTest.
    @Test
    void reportedFieldsDelegatesToReportedFieldsByDevice() {
        CabinEventService rawEventService = new CabinEventService(
            new JdbcTemplate(new SimpleDriverDataSource(
                new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
        rawEventService.save(new CabinEvent("evt-combo", "z2m-humid_mech", "TELEMETRY",
            "INFO", Instant.now(), Map.of("humidity", 75, "temperature", 20)));

        java.util.Map<String, List<String>> result = controller.reportedFields();

        assertEquals(java.util.Set.of("humidity", "temperature"), java.util.Set.copyOf(result.get("z2m-humid_mech")));
    }
}
