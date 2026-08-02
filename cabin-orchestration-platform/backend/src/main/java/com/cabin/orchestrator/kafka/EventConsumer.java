package com.cabin.orchestrator.kafka;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Consumes cabin.events.raw (published by EventPublisher) and persists into
 * Postgres via CabinEventService — closing the gap where events were being
 * published but nothing ever read them back out (cabin_event table existed
 * with zero rows, /api/events served hardcoded demo data).
 *
 * Raw KafkaConsumer in a background thread, matching EventPublisher's raw-
 * KafkaProducer style rather than pulling in spring-kafka for one consumer —
 * same reasoning MqttBridgeService already uses for its own connect()
 * pattern (a plain @PostConstruct-started background loop).
 */
@Component
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);
    private static final String TOPIC = "cabin.events.raw";

    @Value("${cabin.kafka.bootstrapServers:localhost:9092}")
    private String bootstrapServers;

    private final CabinEventService eventService;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread pollThread;

    public EventConsumer(CabinEventService eventService) {
        this.eventService = eventService;
    }

    @PostConstruct
    public void start() {
        running.set(true);
        pollThread = new Thread(this::pollLoop, "cabin-event-consumer");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void pollLoop() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "cabin-backend-events");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        // Only replay history the first time this consumer group ever runs —
        // after that, offsets are tracked normally and this has no effect.
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(TOPIC));
            log.info("Event consumer subscribed to {} on {}", TOPIC, bootstrapServers);
            while (running.get()) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(2));
                for (ConsumerRecord<String, String> record : records) {
                    try {
                        CabinEvent event = mapper.readValue(record.value(), CabinEvent.class);
                        eventService.save(event);
                        log.debug("Saved event {} to Postgres", event.eventId());
                    } catch (Exception e) {
                        log.warn("Failed to persist event from {}: {}", TOPIC, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Event consumer stopped unexpectedly: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (pollThread != null) {
            try { pollThread.join(Duration.ofSeconds(5)); } catch (InterruptedException ignored) {}
        }
    }
}
