package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.integrations.cameras.BlinkLiveviewService;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-10-19: a backend restart wiped driveway's lastSeen and, with no motion
 * on a quiet day, DeviceHealthMonitor marked the Blink camera off-schedule five
 * minutes later and OFFLINE at fifteen. Frigate's cabin/camera/stats message
 * (every camera it runs, idle Blink ones at 0.0 fps) is now the heartbeat.
 *
 * Needs no Docker (unlike MqttBridgeServiceTest): the reconciliation service
 * and presence service are built on a mocked JdbcTemplate because the stats
 * path touches neither.
 */
class MqttBridgeCameraStatsTest {

    private static final String STATS = """
        {"cameras": {
           "cabin_outside_reolink": {"camera_fps": 5.1, "pid": 991},
           "driveway": {"camera_fps": 0.0, "process_fps": 5.1, "pid": 1114},
           "home_aldrich_front": {"camera_fps": 0.0, "pid": 1258}}}
        """;

    private DeviceRegistry registry;
    private MqttBridgeService bridge;
    private DeviceHealthMonitor monitor;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PresenceSignalRegistry signals = new PresenceSignalRegistry();
        BlinkLiveviewService blink = mock(BlinkLiveviewService.class);
        when(blink.blinkCameraMap()).thenReturn(Map.of(
            "driveway", "Outdoor 4 - DHEE", "home_aldrich_front", "AldrichFront"));
        bridge = new MqttBridgeService(registry, mock(EventPublisher.class),
            new PresenceService(jdbc, signals), signals, new SecurityStateRegistry(),
            new FrigateEventReconciliationService(new CabinEventService(jdbc), registry, "http://unused:0", 5, 300, 200),
            blink);
        monitor = new DeviceHealthMonitor(registry,
            new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry()));
    }

    private void deliverStats(String payload) {
        bridge.messageArrived("cabin/camera/stats", new MqttMessage(payload.getBytes()));
    }

    /** An assigned, enabled Blink camera as it is after a restart: known, but last seen long ago. */
    private void registerDriveway(Duration lastSeenAgo) {
        registry.registerDescriptor(new DeviceDescriptor("driveway", "driveway", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE), "mqtt",
            "cabin/camera/driveway", true, "cabin"));
        registry.update(new DeviceStatus("driveway", DeviceType.CAMERA, "driveway", "UNKNOWN",
            Instant.now().minus(lastSeenAgo), Map.of(), "cabin"));
    }

    @Test
    void statsRefreshesBlinkCamerasEvenAtZeroFps() {
        registerDriveway(Duration.ofMinutes(16));

        deliverStats(STATS);

        DeviceStatus driveway = registry.get("driveway");
        assertEquals("ONLINE", driveway.state());
        assertTrue(Duration.between(driveway.lastSeen(), Instant.now()).getSeconds() < 5);
        assertNotNull(registry.get("home_aldrich_front"), "a Blink camera in stats is registered like any other touched camera");
    }

    @Test
    void statsDoesNotRegisterOrTouchNonBlinkCameras() {
        deliverStats(STATS);

        assertNull(registry.get("cabin_outside_reolink"),
            "Frigate's own names for continuous cameras can differ from platform ids; stats must not create strays");
    }

    @Test
    void unrelatedOrMalformedStatsPayloadsChangeNothing() {
        deliverStats("not json");
        deliverStats("{\"detectors\": {}}");
        deliverStats("{\"cameras\": {}}");

        assertNull(registry.get("driveway"));
    }

    @Test
    void quietBlinkCameraStaysOnScheduleAcrossARestartWhenFrigateKeepsReporting() {
        // Restart: registry rebuilt, driveway last seen at boot, then a quiet
        // 16 minutes with Frigate stats arriving about once a minute.
        registerDriveway(Duration.ofMinutes(16));
        deliverStats(STATS);

        monitor.checkHealth();

        assertEquals(CheckinStatus.ON_SCHEDULE, monitor.getCheckinStatuses().get("driveway"));
        assertEquals("ONLINE", registry.get("driveway").state());
    }

    @Test
    void blinkCameraStillGoesOfflineWhenNothingIsReportingAnymore() {
        // Guards against over-exempting: if Frigate (and so stats) stops, the
        // camera must still age out exactly as before.
        registerDriveway(Duration.ofMinutes(16));

        monitor.checkHealth();

        assertEquals(CheckinStatus.MISSED, monitor.getCheckinStatuses().get("driveway"));
        assertEquals("OFFLINE", registry.get("driveway").state());
    }
}
