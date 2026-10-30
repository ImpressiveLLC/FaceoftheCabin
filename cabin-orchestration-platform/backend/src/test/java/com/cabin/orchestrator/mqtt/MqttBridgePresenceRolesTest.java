package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.integrations.cameras.BlinkLiveviewService;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.LocationRole;
import com.cabin.orchestrator.presence.LocationRoleService;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.presence.TrackedPersonStore;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** W-38 slice 1: a maintenance-only arrival is not a presence change (D24 R-LR-2), at the MQTT bridge. No Docker needed. */
class MqttBridgePresenceRolesTest {

    static class MemoryStore implements TrackedPersonStore {
        final Map<String, Set<LocationRole>> rows = new HashMap<>();
        public List<Assignment> findAll() { return List.of(); }
        public Set<LocationRole> rolesOf(String personId, String location) { return rows.getOrDefault(personId + "@" + location, Set.of()); }
        public void setRoles(String personId, String location, Set<LocationRole> roles) { rows.put(personId + "@" + location, roles); }
    }

    private final MemoryStore store = new MemoryStore();
    private EventPublisher events;
    private PresenceSignalRegistry signals;
    private MqttBridgeService bridge;

    @BeforeEach
    void setUp() {
        events = mock(EventPublisher.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        DeviceRegistry registry = new DeviceRegistry(List.of());
        signals = new PresenceSignalRegistry(new LocationRoleService(store), events);
        bridge = new MqttBridgeService(registry, events, new PresenceService(jdbc, signals), signals, new SecurityStateRegistry(),
            new FrigateEventReconciliationService(new CabinEventService(jdbc), registry, "http://unused:0", 5, 300, 200),
            mock(BlinkLiveviewService.class));
    }

    private void deliver(String topic, String payload, boolean retained) {
        MqttMessage message = new MqttMessage(payload.getBytes());
        message.setRetained(retained);
        bridge.messageArrived(topic, message);
    }

    private List<String> publishedTypes() {
        ArgumentCaptor<CabinEvent> captor = ArgumentCaptor.forClass(CabinEvent.class);
        verify(events, atLeastOnce()).publish(captor.capture());
        return captor.getAllValues().stream().map(CabinEvent::eventType).toList();
    }

    @Test
    void aMaintenanceOnlyArrivalPublishesNoPresenceChangedOnlyAVisit() {
        store.setRoles("contractor", "cabin", EnumSet.of(LocationRole.MAINTENANCE));
        deliver("cabin/presence/contractor", "home", false);

        List<String> types = publishedTypes();
        assertTrue(types.contains("MAINTENANCE_VISIT"), types.toString());
        assertTrue(!types.contains("PRESENCE_CHANGED"), "a guest's arrival must not fire presence-arrived workflows: " + types);
    }

    @Test
    void aPrimaryArrivalStillPublishesPresenceChanged() {
        store.setRoles("nate", "cabin", EnumSet.of(LocationRole.PRIMARY));
        deliver("cabin/presence/nate", "home", false);

        List<String> types = publishedTypes();
        assertTrue(types.contains("PRESENCE_CHANGED"), types.toString());
        assertTrue(!types.contains("MAINTENANCE_VISIT"), types.toString());
    }

    @Test
    void anUnregisteredArrivalIsCountedSoItStillPublishesPresenceChanged() {
        deliver("cabin/presence/newphone", "home", false);

        assertTrue(publishedTypes().contains("PRESENCE_CHANGED"));
    }

    @Test
    void aRetainedReplayOfAMaintenanceArrivalWritesNoVisit() {
        store.setRoles("contractor", "cabin", EnumSet.of(LocationRole.MAINTENANCE));
        deliver("cabin/presence/contractor", "home", true);

        verify(events, never()).publish(any());
    }

    @Test
    void aMaintenanceHeartbeatDoesNotMakeThePresenceSignalLookFresh() {
        store.setRoles("contractor", "cabin", EnumSet.of(LocationRole.MAINTENANCE));
        deliver("cabin/presence/contractor/last_seen", Instant.now().toString(), false);
        assertTrue(signals.latestSeen().isEmpty());

        store.setRoles("nate", "cabin", EnumSet.of(LocationRole.PRIMARY));
        Instant seen = Instant.parse("2026-10-30T10:00:00Z");
        deliver("cabin/presence/nate/last_seen", seen.toString(), false);
        assertEquals(seen, signals.latestSeen().orElseThrow());
    }
}
