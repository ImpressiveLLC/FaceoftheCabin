package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Subscribes to all cabin/device/# and cabin/camera/# MQTT topics.
 * Routes telemetry → DeviceRegistry.update() and → Kafka cabin.events.raw.
 *
 * Topic contract (from docs/device-topic-contract.md):
 *   cabin/device/{deviceId}/state      — full state snapshot
 *   cabin/device/{deviceId}/telemetry  — metric payload (psi, temp, etc.)
 *   cabin/event/{severity}             — direct event injection
 *   cabin/camera/{cameraId}/motion     — Frigate motion events
 *   cabin/system/health                — watchdog health reports
 */
@Service
public class MqttBridgeService implements MqttCallback {

    private static final Logger log = LoggerFactory.getLogger(MqttBridgeService.class);

    @Value("${cabin.mqtt.brokerUrl:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${cabin.mqtt.clientId:cabin-orchestrator}")
    private String clientId;

    private MqttClient client;
    private final DeviceRegistry registry;
    private final EventPublisher eventPublisher;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public MqttBridgeService(DeviceRegistry registry, EventPublisher eventPublisher) {
        this.registry = registry;
        this.eventPublisher = eventPublisher;
    }

    @PostConstruct
    public void connect() {
        try {
            client = new MqttClient(brokerUrl, clientId + "-" + UUID.randomUUID());
            client.setCallback(this);
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(false);
            client.connect(opts);
            client.subscribe("cabin/device/#", 1);
            client.subscribe("cabin/camera/#", 1);
            client.subscribe("cabin/event/#", 1);
            client.subscribe("cabin/system/#", 0);
            log.info("MQTT bridge connected to {}", brokerUrl);
        } catch (MqttException e) {
            log.error("MQTT connect failed: {}", e.getMessage());
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            String payload = new String(message.getPayload());
            Map<String, Object> data = mapper.readValue(payload, Map.class);
            String[] parts = topic.split("/");

            if (parts.length >= 3 && "device".equals(parts[1])) {
                String deviceId = parts[2];
                String msgType = parts.length >= 4 ? parts[3] : "state";
                handleDeviceMessage(deviceId, msgType, data);
            } else if (parts.length >= 3 && "camera".equals(parts[1])) {
                handleCameraEvent(parts[2], data);
            } else if (parts.length >= 3 && "event".equals(parts[1])) {
                handleDirectEvent(parts[2], data);
            }
        } catch (Exception e) {
            log.warn("Failed to process MQTT message on {}: {}", topic, e.getMessage());
        }
    }

    private void handleDeviceMessage(String deviceId, String msgType, Map<String, Object> data) {
        DeviceStatus existing = registry.get(deviceId);
        if (existing == null) {
            // Auto-register unknown device — type inferred from payload keys, location from topic prefix
            DeviceType type = inferType(data);
            String loc = deviceId.startsWith("home-") ? "home" : "cabin";
            existing = new DeviceStatus(deviceId, type, deviceId, "UNKNOWN", Instant.now(), Map.of(), loc);
            log.info("Auto-registered new device: {} as {} at {}", deviceId, type, loc);
        }
        Map<String, Object> attrs = new LinkedHashMap<>(existing.attributes());
        attrs.putAll(data);
        String state = determineState(data, existing.type());
        registry.update(new DeviceStatus(deviceId, existing.type(), existing.name(), state,
            Instant.now(), attrs, existing.location()));

        // Publish to Kafka for rules engine consumption
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), deviceId, "TELEMETRY", "INFO", Instant.now(), data);
        eventPublisher.publish(event);
    }

    private void handleCameraEvent(String cameraId, Map<String, Object> data) {
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), cameraId, "MOTION_DETECTED", "INFO", Instant.now(), data);
        eventPublisher.publish(event);
    }

    private void handleDirectEvent(String severity, Map<String, Object> data) {
        String deviceId = String.valueOf(data.getOrDefault("deviceId", "system"));
        String eventType = String.valueOf(data.getOrDefault("event", "UNKNOWN"));
        CabinEvent event = new CabinEvent(
            UUID.randomUUID().toString(), deviceId, eventType,
            severity.toUpperCase(), Instant.now(), data);
        eventPublisher.publish(event);
    }

    private DeviceType inferType(Map<String, Object> data) {
        if (data.containsKey("psi"))     return DeviceType.WATER_PRESSURE_SENSOR;
        if (data.containsKey("temp_f"))  return DeviceType.TEMPERATURE_SENSOR;
        if (data.containsKey("alarm"))   return DeviceType.SMOKE_ALARM;
        if (data.containsKey("locked"))  return DeviceType.LOCK;
        if (data.containsKey("motion"))  return DeviceType.MOTION_SENSOR;
        return DeviceType.HOME_ASSISTANT_ENTITY;
    }

    private String determineState(Map<String, Object> data, DeviceType type) {
        Object alarm = data.get("alarm");
        if (Boolean.TRUE.equals(alarm)) return "ALARM";
        return "ONLINE";
    }

    @Override public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {}", cause.getMessage());
    }

    @Override public void deliveryComplete(IMqttDeliveryToken token) {}

    @PreDestroy
    public void disconnect() {
        try { if (client != null && client.isConnected()) client.disconnect(); }
        catch (MqttException ignored) {}
    }
}
