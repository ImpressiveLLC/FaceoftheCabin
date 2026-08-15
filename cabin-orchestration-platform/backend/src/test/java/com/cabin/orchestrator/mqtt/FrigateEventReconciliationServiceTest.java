package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies against a real local HTTP server (not a live Frigate instance)
 * that FrigateEventReconciliationService's REST reconciliation actually
 * does what its own javadoc claims: fetches /api/events, upserts through
 * the same CabinEventService.upsert() path MqttBridgeService uses,
 * advances its cursor to the newest event seen, re-queries with the
 * configured overlap (not just the raw cursor) on the next run, and
 * surfaces health state instead of failing silently. Same
 * local-HttpServer pattern as NtfyAlertPublisherTest -- see that class's
 * own comment for why this codebase tests outbound HTTP this way instead
 * of mocking the client.
 *
 * MqttBridgeServiceTest covers the MQTT-arrival half of upsertDetection()
 * (dedup, skip-no-id, hasClip transition) already -- this class is
 * specifically the REST/reconcile/cursor/health half.
 */
@Testcontainers
class FrigateEventReconciliationServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private HttpServer server;
    private String baseUrl;
    private final List<String> receivedQueries = new CopyOnWriteArrayList<>();
    private final Deque<String> responseBodies = new ArrayDeque<>();
    private volatile int responseStatus = 200;

    private CabinEventService eventService;
    private DeviceRegistry registry;

    @BeforeEach
    void startServerAndDb() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/events", exchange -> {
            receivedQueries.add(exchange.getRequestURI().getQuery());
            String body = responseBodies.isEmpty() ? "[]" : responseBodies.poll();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();

        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        eventService = new CabinEventService(jdbc);
        registry = new DeviceRegistry(List.of());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private FrigateEventReconciliationService service(int overlapSeconds) {
        return new FrigateEventReconciliationService(eventService, registry, baseUrl, 5, overlapSeconds, 200);
    }

    @Test
    void initialBackfillPersistsEventsFromFrigateRestAndTagsCameraLocation() {
        responseBodies.add("""
            [
              {"id":"1723000010.0-aa","camera":"driveway","label":"person","start_time":1723000010.0,"has_clip":true,"has_snapshot":true,"top_score":0.88},
              {"id":"1723000020.0-bb","camera":"home_aldrich_front","label":"car","start_time":1723000020.0,"has_clip":false,"has_snapshot":false,"top_score":0.5}
            ]""");

        service(300).initialBackfill();

        List<CabinEvent> events = eventService.recent(null, 10, Instant.EPOCH);
        assertThat(events).extracting(CabinEvent::eventId)
            .containsExactlyInAnyOrder("frigate:1723000010.0-aa", "frigate:1723000020.0-bb");

        CabinEvent driveway = events.stream().filter(e -> e.sourceDeviceId().equals("driveway")).findFirst().orElseThrow();
        assertThat(driveway.payload()).containsEntry("hasClip", true).containsEntry("hasSnapshot", true);

        assertThat(registry.get("driveway").location()).isEqualTo("cabin");
        assertThat(registry.get("home_aldrich_front").location()).isEqualTo("home");
    }

    @Test
    void initialBackfillRequestsTheConfiguredWindowAndPageLimit() {
        responseBodies.add("[]");

        service(300).initialBackfill();

        assertThat(receivedQueries).hasSize(1);
        assertThat(receivedQueries.get(0)).contains("after=").contains("limit=200");
    }

    // The whole point of the overlap window -- absorbs clock drift and
    // partial-failure gaps by never trusting the raw cursor alone on the
    // next run (see the constructor's own comment on overlapSeconds).
    @Test
    void periodicReconcileQueriesWithOverlapNotJustTheRawCursor() {
        long eventEpoch = 2_000_000_000L;
        responseBodies.add("""
            [{"id":"%d.0-cc","camera":"driveway","label":"person","start_time":%d.0,"has_clip":true,"has_snapshot":true}]"""
            .formatted(eventEpoch, eventEpoch));
        responseBodies.add("[]");

        FrigateEventReconciliationService svc = service(120);
        svc.initialBackfill();
        svc.periodicReconcile();

        assertThat(receivedQueries).hasSize(2);
        assertThat(receivedQueries.get(1)).contains("after=" + (eventEpoch - 120));
    }

    @Test
    void cursorAdvancesToTheNewestStartTimeSeenNotTheLast() {
        responseBodies.add("""
            [
              {"id":"1723000030.0-dd","camera":"driveway","label":"person","start_time":1723000030.0,"has_clip":false,"has_snapshot":false},
              {"id":"1723000010.0-ee","camera":"driveway","label":"person","start_time":1723000010.0,"has_clip":false,"has_snapshot":false}
            ]""");
        responseBodies.add("[]");

        FrigateEventReconciliationService svc = service(300);
        svc.initialBackfill();
        svc.periodicReconcile();

        assertThat(receivedQueries.get(1)).contains("after=" + (1723000030L - 300));
    }

    @Test
    void successfulRunReportsHealthyStatus() {
        responseBodies.add("""
            [{"id":"1723000040.0-ff","camera":"driveway","label":"person","start_time":1723000040.0,"has_clip":true,"has_snapshot":true}]""");

        FrigateEventReconciliationService svc = service(300);
        svc.initialBackfill();

        Map<String, Object> status = svc.healthStatus();
        assertThat(status.get("healthy")).isEqualTo(true);
        assertThat(status.get("lastError")).isNull();
        assertThat(status.get("lastUpsertedCount")).isEqualTo(1);
        assertThat(status.get("mostRecentFrigateEventSeenAt")).isEqualTo(Instant.ofEpochSecond(1723000040L));
        assertThat(status.get("lastRunAt")).isNotNull();
        assertThat(status.get("lastSuccessAt")).isNotNull();
    }

    @Test
    void nonOkResponseRecordsErrorWithoutCrashingAndPersistsNothing() {
        responseStatus = 500;
        responseBodies.add("oops");

        FrigateEventReconciliationService svc = service(300);
        svc.initialBackfill(); // must not throw -- this runs from @PostConstruct in production

        Map<String, Object> status = svc.healthStatus();
        assertThat(status.get("healthy")).isEqualTo(false);
        assertThat((String) status.get("lastError")).contains("500");
        assertThat(eventService.recent(null, 10, Instant.EPOCH)).isEmpty();
    }

    @Test
    void unreachableServerRecordsErrorWithoutCrashing() {
        FrigateEventReconciliationService svc = new FrigateEventReconciliationService(
            eventService, registry, "http://localhost:1", 5, 300, 200);

        svc.initialBackfill(); // must not throw

        Map<String, Object> status = svc.healthStatus();
        assertThat(status.get("healthy")).isEqualTo(false);
        assertThat(status.get("lastError")).isNotNull();
    }

    @Test
    void eventWithNoIdIsNotPersisted() {
        responseBodies.add("""
            [{"camera":"driveway","label":"person","start_time":1723000050.0,"has_clip":true,"has_snapshot":true}]""");

        service(300).initialBackfill();

        assertThat(eventService.recent(null, 10, Instant.EPOCH)).isEmpty();
    }

    // Same idempotent-upsert guarantee MqttBridgeServiceTest verifies for
    // the MQTT path, exercised here through the REST path instead --
    // periodicReconcile() re-covering the overlap window and re-seeing the
    // same event must update, not duplicate.
    @Test
    void reReconcilingTheSameEventInTheOverlapWindowUpdatesInsteadOfDuplicating() {
        responseBodies.add("""
            [{"id":"1723000060.0-gg","camera":"driveway","label":"person","start_time":1723000060.0,"has_clip":false,"has_snapshot":false}]""");
        responseBodies.add("""
            [{"id":"1723000060.0-gg","camera":"driveway","label":"person","start_time":1723000060.0,"has_clip":true,"has_snapshot":true}]""");

        FrigateEventReconciliationService svc = service(300);
        svc.initialBackfill();
        svc.periodicReconcile();

        List<CabinEvent> events = eventService.recent(null, 10, Instant.EPOCH);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).payload()).containsEntry("hasClip", true);
    }
}
