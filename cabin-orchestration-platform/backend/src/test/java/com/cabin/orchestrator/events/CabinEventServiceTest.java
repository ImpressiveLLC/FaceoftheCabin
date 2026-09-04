package com.cabin.orchestrator.events;

import com.cabin.orchestrator.devices.JdbcDeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.model.ConfirmationSource;
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
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        // Reset between tests same as cabin_event above -- several tests below
        // reuse device ids like "z2m-humid_mech" and would otherwise see a
        // previous test's leftover reporting-relationship rows.
        jdbc.execute("DROP TABLE IF EXISTS device_reporting_relationship");
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

    // 2026-08-15: added for FrigateEventReconciliationService, which
    // upserts the same Frigate detection (keyed frigate:{id}) more than
    // once as it evolves -- e.g. hasClip starts false and flips true once
    // Frigate finishes encoding the clip. save()'s ON CONFLICT DO NOTHING
    // would mean whichever report arrived first wins forever, even if it
    // was the least complete one -- exactly the bug this method exists to
    // avoid. See CabinEventService.upsert()'s own javadoc.
    @Test
    void upsertUpdatesExistingRowInsteadOfIgnoringIt() {
        Instant firstSeen = Instant.now().minusSeconds(30);
        service.upsert(new CabinEvent("frigate:1723-abc", "driveway", "DETECTION_NEW",
            "INFO", firstSeen, Map.of("hasClip", false, "hasSnapshot", false)));

        Instant updated = Instant.now();
        service.upsert(new CabinEvent("frigate:1723-abc", "driveway", "DETECTION_END",
            "WARN", updated, Map.of("hasClip", true, "hasSnapshot", true)));

        // setUp() already seeds 5 unrelated rows (including one for
        // "driveway") -- filter to this test's own id rather than
        // asserting total table size.
        List<CabinEvent> rows = service.recent(null, 10, Instant.now().minusSeconds(60)).stream()
            .filter(e -> e.eventId().equals("frigate:1723-abc")).toList();
        assertThat(rows).hasSize(1);
        CabinEvent row = rows.get(0);
        assertThat(row.eventType()).isEqualTo("DETECTION_END");
        assertThat(row.severity()).isEqualTo("WARN");
        assertThat(row.payload()).containsEntry("hasClip", true).containsEntry("hasSnapshot", true);
    }

    @Test
    void upsertInsertsNormallyWhenTheIdIsNew() {
        service.upsert(new CabinEvent("frigate:new-id", "driveway", "DETECTION_NEW",
            "INFO", Instant.now(), Map.of()));

        List<CabinEvent> rows = service.recent("driveway", 10, Instant.now().minusSeconds(60));
        assertThat(rows).extracting(CabinEvent::eventId).contains("frigate:new-id");
    }

    // The reverse guarantee that makes the two-method split meaningful --
    // save() must stay DO NOTHING (first write wins) for every other event
    // source, which relies on a fresh random UUID per event never
    // colliding and has no reason to want a "duplicate" to overwrite an
    // earlier row.
    @Test
    void saveStillIgnoresADuplicateIdInsteadOfOverwriting() {
        save(service, "dup-1", "front_door", "STATE_CHANGE", "INFO", Instant.now());
        save(service, "dup-1", "front_door", "STATE_CHANGE", "CRITICAL", Instant.now());

        List<CabinEvent> rows = service.recent("front_door", 10, Instant.now().minusSeconds(60));
        List<CabinEvent> matching = rows.stream().filter(r -> r.eventId().equals("dup-1")).toList();
        assertThat(matching).hasSize(1);
        assertThat(matching.get(0).severity()).isEqualTo("INFO");
    }

    // 2026-08-25: dailyAggregates() -- built for a real historical trend
    // view (weeks of temp/humidity, insurance-claim-grade evidence), which
    // recent()'s 200-row cap can't serve at a ~10-15min sample interval.
    private void saveTelemetry(String id, String device, Instant ts, Map<String, Object> payload) {
        service.save(new CabinEvent(id, device, "TELEMETRY", "INFO", ts, payload));
    }

    @Test
    void averagesMultipleSameDayReadingsIntoOneBucket() {
        Instant today = Instant.now();
        saveTelemetry("t-1", "z2m-humid_mech", today.minusSeconds(3600), Map.of("humidity", 70));
        saveTelemetry("t-2", "z2m-humid_mech", today.minusSeconds(1800), Map.of("humidity", 80));

        List<TelemetryDailyPoint> points = service.dailyAggregates("z2m-humid_mech", "humidity", 7);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).avg()).isEqualTo(75.0);
        assertThat(points.get(0).min()).isEqualTo(70.0);
        assertThat(points.get(0).max()).isEqualTo(80.0);
        assertThat(points.get(0).sampleCount()).isEqualTo(2);
    }

    @Test
    void splitsReadingsFromDifferentDaysIntoSeparateBuckets() {
        Instant now = Instant.now();
        saveTelemetry("t-1", "z2m-humid_mech", now.minus(java.time.Duration.ofDays(2)), Map.of("humidity", 60));
        saveTelemetry("t-2", "z2m-humid_mech", now, Map.of("humidity", 90));

        List<TelemetryDailyPoint> points = service.dailyAggregates("z2m-humid_mech", "humidity", 7);

        assertThat(points).hasSize(2);
        assertThat(points).extracting(TelemetryDailyPoint::avg).containsExactlyInAnyOrder(60.0, 90.0);
    }

    @Test
    void ignoresReadingsOutsideTheRequestedWindow() {
        Instant now = Instant.now();
        saveTelemetry("old", "z2m-humid_mech", now.minus(java.time.Duration.ofDays(40)), Map.of("humidity", 10));
        saveTelemetry("recent", "z2m-humid_mech", now, Map.of("humidity", 90));

        List<TelemetryDailyPoint> points = service.dailyAggregates("z2m-humid_mech", "humidity", 30);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).avg()).isEqualTo(90.0);
    }

    @Test
    void isScopedToOneDeviceAndOneField() {
        Instant now = Instant.now();
        saveTelemetry("this-device", "z2m-humid_mech", now, Map.of("humidity", 70, "temperature", 18));
        saveTelemetry("other-device", "z2m-humid_kitchen", now, Map.of("humidity", 40));

        List<TelemetryDailyPoint> humidity = service.dailyAggregates("z2m-humid_mech", "humidity", 7);
        List<TelemetryDailyPoint> temperature = service.dailyAggregates("z2m-humid_mech", "temperature", 7);

        assertThat(humidity).singleElement().satisfies(p -> assertThat(p.avg()).isEqualTo(70.0));
        assertThat(temperature).singleElement().satisfies(p -> assertThat(p.avg()).isEqualTo(18.0));
    }

    @Test
    void skipsNonNumericValuesInsteadOfFailingTheWholeQuery() {
        Instant now = Instant.now();
        saveTelemetry("bad", "z2m-humid_mech", now, Map.of("humidity", "unavailable"));
        saveTelemetry("good", "z2m-humid_mech", now, Map.of("humidity", 55));

        List<TelemetryDailyPoint> points = service.dailyAggregates("z2m-humid_mech", "humidity", 7);

        assertThat(points).hasSize(1);
        assertThat(points.get(0).avg()).isEqualTo(55.0);
        assertThat(points.get(0).sampleCount()).isEqualTo(1);
    }

    @Test
    void returnsNoBucketsForADeviceWithNoMatchingReadings() {
        List<TelemetryDailyPoint> points = service.dailyAggregates("nonexistent-device", "humidity", 7);
        assertThat(points).isEmpty();
    }

    // 2026-08-27: reportedFieldsByDevice() -- the real, observed-data
    // ground truth for the field/device picker, replacing
    // DeviceType.telemetryFields()'s static per-type guess (see its own
    // and this method's doc for the z2m-temp_outside_lowest case that
    // exposed the guess as wrong).
    @Test
    void reportedFieldsByDeviceReturnsExactlyTheFieldsEachDeviceHasActuallyLogged() {
        Instant now = Instant.now();
        saveTelemetry("combo-1", "z2m-humid_mech", now, Map.of("humidity", 70, "temperature", 18));
        saveTelemetry("temp-only-1", "z2m-temp_outside_lowest", now, Map.of("temperature", 12));

        Map<String, List<String>> result = service.reportedFieldsByDevice();

        assertThat(result.get("z2m-humid_mech")).containsExactlyInAnyOrder("humidity", "temperature");
        assertThat(result.get("z2m-temp_outside_lowest")).containsExactly("temperature");
    }

    @Test
    void reportedFieldsByDeviceOmitsNonNumericAndNonTelemetryEntries() {
        Instant now = Instant.now();
        saveTelemetry("bad-value", "z2m-humid_mech", now, Map.of("humidity", "unavailable"));
        service.save(new CabinEvent("state-change", "z2m-humid_mech", "STATE_CHANGE", "INFO", now, Map.of("humidity", 70)));

        Map<String, List<String>> result = service.reportedFieldsByDevice();

        assertThat(result.get("z2m-humid_mech")).isNull();
    }

    @Test
    void reportedFieldsByDeviceHasNoEntryForADeviceThatHasNeverLoggedAnything() {
        Map<String, List<String>> result = service.reportedFieldsByDevice();
        assertThat(result).doesNotContainKey("z2m-never-reported-anything");
    }

    // Issue #31: reportedFieldsByDevice() also reconciles each recognized
    // field into a real, queryable device_reporting_relationship row --
    // the shared `service` field above uses the no-op convenience
    // constructor (see CabinEventService's own comment on why), so this
    // needs its own instance wired to a real repository to observe it.
    @Test
    void reportedFieldsByDevicePersistsRecognizedFieldsAsEmpiricalObservation() {
        JdbcDeviceReportingRelationshipRepository reportingRepository = new JdbcDeviceReportingRelationshipRepository(jdbc);
        CabinEventService serviceWithPersistence = new CabinEventService(jdbc, reportingRepository);
        saveTelemetry("combo-persist-1", "z2m-humid_persist", Instant.now(), Map.of("humidity", 70, "temperature", 18));

        serviceWithPersistence.reportedFieldsByDevice();

        var saved = reportingRepository.findByDevice("z2m-humid_persist");
        assertThat(saved).hasSize(2);
        assertThat(saved).allMatch(r -> r.confirmationSource() == ConfirmationSource.EMPIRICAL_OBSERVATION);
    }

    @Test
    void reportedFieldsByDeviceNeverDowngradesAnAlreadyVendorSpecConfirmedField() {
        JdbcDeviceReportingRelationshipRepository reportingRepository = new JdbcDeviceReportingRelationshipRepository(jdbc);
        reportingRepository.upsert(new com.cabin.orchestrator.devices.model.DeviceReportingRelationship(
            "z2m-humid_confirmed", "humidity", "humidity", ConfirmationSource.VENDOR_SPEC, Instant.now()));
        CabinEventService serviceWithPersistence = new CabinEventService(jdbc, reportingRepository);
        saveTelemetry("combo-persist-2", "z2m-humid_confirmed", Instant.now(), Map.of("humidity", 70));

        serviceWithPersistence.reportedFieldsByDevice();

        var found = reportingRepository.findByDevice("z2m-humid_confirmed").get(0);
        assertThat(found.confirmationSource()).isEqualTo(ConfirmationSource.VENDOR_SPEC);
    }

    // WSJF bug #2 (Kidde false OFFLINE): DeviceHealthMonitor's own
    // liveness cross-check calls this directly against real Postgres.
    @Test
    void hasRecentEventIsTrueOnlyForAMatchingDeviceAndEventTypeWithinTheWindow() {
        save(service, "evt-telemetry-1", "kidde-co", "TELEMETRY", "INFO", Instant.now().minusSeconds(30));

        assertThat(service.hasRecentEvent("kidde-co", "TELEMETRY", Instant.now().minus(java.time.Duration.ofMinutes(5))))
            .isTrue();
        assertThat(service.hasRecentEvent("kidde-co", "TELEMETRY", Instant.now().plusSeconds(5)))
            .isFalse();
        assertThat(service.hasRecentEvent("some-other-device", "TELEMETRY", Instant.now().minus(java.time.Duration.ofMinutes(5))))
            .isFalse();
        assertThat(service.hasRecentEvent("kidde-co", "KIDDE_CO_ALARM_CHANGED", Instant.now().minus(java.time.Duration.ofMinutes(5))))
            .isFalse();
    }
}
