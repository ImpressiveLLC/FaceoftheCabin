package com.cabin.orchestrator.events;

import com.cabin.orchestrator.kafka.EventConsumer;
import com.cabin.orchestrator.kafka.EventPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The full CabinEvent durability path — EventPublisher -> Kafka ->
 * EventConsumer -> CabinEventService -> Postgres -> query back out —
 * exercised against real Postgres/Kafka containers instead of mocks.
 * This automates the same check done by hand via mosquitto_pub against the
 * live M920q when the severity classifier + ntfy wiring first shipped
 * (see docs/MAINTENANCE.md).
 *
 * MQTT is deliberately not part of this test: Zigbee2MqttAdapter and
 * MqttBridgeService both already fail soft when no broker is reachable
 * (connect() catches MqttException and logs a warning, never throws), so
 * they're out of scope here — this test targets the publish/persist path
 * shared by every event source, not any one adapter's message parsing.
 */
@Testcontainers
class EventPipelineIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    private EventPublisher publisher;
    private EventConsumer consumer;
    private CabinEventService eventService;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        eventService = new CabinEventService(jdbc);

        publisher = new EventPublisher();
        ReflectionTestUtils.setField(publisher, "bootstrapServers", kafka.getBootstrapServers());
        publisher.init();

        // "" base URL + empty topic = NtfyAlertPublisher is a guaranteed no-op
        // here; this test is about persistence, not push delivery (see
        // NtfyAlertPublisherTest for that).
        NtfyAlertPublisher ntfy = new NtfyAlertPublisher("", "http://localhost:0");
        consumer = new EventConsumer(eventService, ntfy);
        ReflectionTestUtils.setField(consumer, "bootstrapServers", kafka.getBootstrapServers());
        consumer.start();
    }

    @AfterEach
    void tearDown() {
        consumer.stop();
        publisher.close();
    }

    @Test
    void publishedEventIsPersistedAndQueryableBySeverity() throws InterruptedException {
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), "test-device", "TEST_EVENT", "CRITICAL",
            Instant.now(), Map.of("water_leak", true));

        publisher.publish(event);

        List<CabinEvent> found = awaitEvent(event.eventId());

        assertThat(found).isNotEmpty();
        CabinEvent persisted = found.get(0);
        assertThat(persisted.severity()).isEqualTo("CRITICAL");
        assertThat(persisted.sourceDeviceId()).isEqualTo("test-device");
        assertThat(persisted.payload()).containsEntry("water_leak", true);
    }

    private List<CabinEvent> awaitEvent(String eventId) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(15);
        while (Instant.now().isBefore(deadline)) {
            List<CabinEvent> recent = eventService.recent("test-device", 10, Instant.now().minusSeconds(60));
            List<CabinEvent> match = recent.stream().filter(e -> e.eventId().equals(eventId)).toList();
            if (!match.isEmpty()) return match;
            Thread.sleep(200);
        }
        return List.of();
    }
}
