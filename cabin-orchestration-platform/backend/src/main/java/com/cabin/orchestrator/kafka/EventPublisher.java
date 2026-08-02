package com.cabin.orchestrator.kafka;

import com.cabin.orchestrator.events.CabinEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Properties;

/**
 * Publishes CabinEvents to Kafka topic cabin.events.raw.
 * Node-RED and the AutomationRuleService consume from this topic.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);
    private static final String TOPIC = "cabin.events.raw";

    @Value("${cabin.kafka.bootstrapServers:localhost:9092}")
    private String bootstrapServers;

    private KafkaProducer<String, String> producer;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @PostConstruct
    public void init() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.RETRIES_CONFIG, 3);
        try {
            producer = new KafkaProducer<>(props);
            log.info("Kafka producer connected to {}", bootstrapServers);
        } catch (Exception e) {
            log.warn("Kafka not available — events will not be published: {}", e.getMessage());
        }
    }

    public void publish(CabinEvent event) {
        if (producer == null) { log.warn("publish() called but producer is null (Kafka unavailable at startup)"); return; }
        try {
            String value = mapper.writeValueAsString(event);
            log.debug("Publishing to {}: {}", TOPIC, value);
            producer.send(new ProducerRecord<>(TOPIC, event.sourceDeviceId(), value),
                (meta, ex) -> {
                    if (ex != null) log.warn("Kafka send failed: {}", ex.getMessage());
                    else log.debug("Kafka send acked: topic={} partition={} offset={}", meta.topic(), meta.partition(), meta.offset());
                });
        } catch (Exception e) {
            log.warn("Event serialization failed: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void close() {
        if (producer != null) producer.close();
    }
}
