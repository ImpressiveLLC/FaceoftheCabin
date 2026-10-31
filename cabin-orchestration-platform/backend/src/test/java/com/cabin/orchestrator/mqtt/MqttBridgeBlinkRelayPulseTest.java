package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.integrations.cameras.BlinkLiveviewService;
import com.cabin.orchestrator.integrations.cameras.BlinkRelayPulse;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** The S23 Companion heartbeat and the Termux relay heartbeat both feed BlinkRelayPulse. No Docker needed. */
class MqttBridgeBlinkRelayPulseTest {

    private MqttBridgeService bridge;
    private BlinkRelayPulse pulse;

    @BeforeEach
    void setUp() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PresenceSignalRegistry signals = new PresenceSignalRegistry();
        bridge = new MqttBridgeService(registry, mock(EventPublisher.class),
            new PresenceService(jdbc, signals), signals, new SecurityStateRegistry(),
            new FrigateEventReconciliationService(new CabinEventService(jdbc), registry, "http://unused:0", 5, 300, 200),
            mock(BlinkLiveviewService.class));
        pulse = new BlinkRelayPulse();
        bridge.setBlinkRelayPulse(pulse);
    }

    private void deliver(String topic, String payload) {
        bridge.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    @Test
    void companionLastSeenForTheConfiguredPersonIsAHeartbeat() {
        deliver("cabin/presence/nate/last_seen", "2026-10-25T10:00:00Z");
        assertEquals(Instant.parse("2026-10-25T10:00:00Z"), pulse.lastSeen().orElseThrow());
    }

    @Test
    void otherPeopleAndOtherLocationsAreNotHeartbeats() {
        deliver("cabin/presence/guest/last_seen", "2026-10-25T10:00:00Z");
        deliver("home/presence/nate/last_seen", "2026-10-25T10:00:00Z");
        assertTrue(pulse.lastSeen().isEmpty());
    }

    @Test
    void termuxRelayHeartbeatTopicIsAHeartbeat() {
        deliver("cabin/blink/relay/heartbeat", "{\"device\":\"x\",\"ts\":\"2026-10-25T11:00:00Z\"}");
        assertEquals(Instant.parse("2026-10-25T11:00:00Z"), pulse.lastSeen().orElseThrow());
    }
}
