package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.kafka.EventPublisher;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the 2026-08-07 finding: handleCameraTopic()
 * never called DeviceRegistry.update() for any camera MQTT message, so a
 * camera's lastSeen was set once (however it first got registered) and
 * never refreshed -- DeviceHealthMonitor's 5-minute camera stale
 * threshold then always fired exactly 5 minutes after that one
 * registration and the camera could never recover on its own.
 *
 * No Testcontainers here -- DeviceRegistry is a plain in-memory
 * ConcurrentHashMap and EventPublisher safely no-ops when constructed
 * without @PostConstruct init() (producer stays null, publish() logs and
 * returns), so this exercises MqttBridgeService.messageArrived() -- the
 * real public entry point Paho calls -- directly against real Kafka/DB.
 */
class MqttBridgeServiceTest {

    private DeviceRegistry registry;
    private MqttBridgeService bridge;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        bridge = new MqttBridgeService(registry, new EventPublisher());
    }

    private void deliver(String topic, String payload) throws Exception {
        bridge.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    @Test
    void motionTopicAutoRegistersAndMarksCameraOnline() throws Exception {
        assertNull(registry.get("driveway"));

        deliver("cabin/camera/driveway/motion", "ON");

        DeviceStatus status = registry.get("driveway");
        assertNotNull(status, "camera should be auto-registered on first motion message");
        assertEquals(DeviceType.CAMERA, status.type());
        assertEquals("ONLINE", status.state());
        assertEquals("cabin", status.location());
    }

    @Test
    void perLabelCountTopicAlsoTouchesTheCamera() throws Exception {
        deliver("cabin/camera/driveway/car", "1");

        DeviceStatus status = registry.get("driveway");
        assertNotNull(status, "camera should be auto-registered from a per-label count topic too");
        assertEquals("ONLINE", status.state());
    }

    @Test
    void repeatedMotionRefreshesLastSeenInsteadOfOnlyRegisteringOnce() throws Exception {
        deliver("cabin/camera/driveway/motion", "OFF");
        Instant firstSeen = registry.get("driveway").lastSeen();

        Thread.sleep(5);
        deliver("cabin/camera/driveway/motion", "ON");
        Instant secondSeen = registry.get("driveway").lastSeen();

        assertTrue(secondSeen.isAfter(firstSeen),
            "a later camera message must push lastSeen forward, or DeviceHealthMonitor's " +
            "5-minute stale threshold will fire and never recover, exactly like the 2026-08-07 incident");
    }

    @Test
    void touchingACameraPreservesItsExistingAttributes() throws Exception {
        deliver("cabin/camera/driveway/motion", "ON");
        // simulate an attribute a future enhancement might attach (e.g. resolution)
        DeviceStatus withAttrs = registry.get("driveway");
        registry.update(new DeviceStatus(withAttrs.deviceId(), withAttrs.type(), withAttrs.name(),
            withAttrs.state(), withAttrs.lastSeen(), java.util.Map.of("resolution", "1080p"), withAttrs.location()));

        deliver("cabin/camera/driveway/car", "2");

        assertEquals("1080p", registry.get("driveway").attributes().get("resolution"),
            "touchCamera() must not clobber attributes set by other paths");
    }

    @Test
    void availableTopicIsIgnoredSinceItDoesNotNameACamera() throws Exception {
        // cabin/camera/available is Frigate's single bridge-wide topic --
        // parts.length == 2, so handleCameraTopic's per-camera branches
        // must not misinterpret it as a camera named "available".
        deliver("cabin/camera/available", "online");

        assertNull(registry.get("available"),
            "the bridge-wide availability topic must never be registered as a camera device");
    }
}
