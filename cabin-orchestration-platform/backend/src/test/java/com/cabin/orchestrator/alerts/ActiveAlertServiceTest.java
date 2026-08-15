package com.cabin.orchestrator.alerts;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActiveAlertServiceTest {

    private DeviceRegistry registry;
    private DeviceHealthMonitor healthMonitor;
    private ActiveAlertService service;

    @BeforeEach
    void setUp() {
        registry = mock(DeviceRegistry.class);
        healthMonitor = mock(DeviceHealthMonitor.class);
        service = new ActiveAlertService(registry, healthMonitor);
    }

    private DeviceStatus status(String id, String state) {
        return new DeviceStatus(id, DeviceType.WATER_LEAK_SENSOR, "Device " + id,
            state, Instant.parse("2026-08-14T12:00:00Z"), Map.of(), "cabin");
    }

    private DeviceDescriptor descriptor(String id, boolean enabled) {
        return new DeviceDescriptor(id, "Device " + id, DeviceType.WATER_LEAK_SENSOR,
            Set.of(DeviceCapability.ALARM), "mqtt", "zigbee2mqtt/" + id, enabled, "cabin");
    }

    private void configure(String id, DeviceLifecycleState lifecycle, boolean enabled) {
        when(registry.descriptor(id)).thenReturn(Optional.of(descriptor(id, enabled)));
        when(registry.lifecycleState(id)).thenReturn(lifecycle);
    }

    @Test
    void onlyAssignedEnabledMissedDeviceProducesCheckinAlert() {
        DeviceStatus candidate = status("candidate", "OFFLINE");
        DeviceStatus available = status("available", "OFFLINE");
        DeviceStatus disabled = status("disabled", "OFFLINE");
        DeviceStatus late = status("late", "ONLINE");
        DeviceStatus missed = status("missed", "OFFLINE");
        when(registry.visible()).thenReturn(List.of(candidate, available, disabled, late, missed));
        configure("candidate", DeviceLifecycleState.CANDIDATE, false);
        configure("available", DeviceLifecycleState.AVAILABLE, false);
        configure("disabled", DeviceLifecycleState.ASSIGNED, false);
        configure("late", DeviceLifecycleState.ASSIGNED, true);
        configure("missed", DeviceLifecycleState.ASSIGNED, true);
        when(healthMonitor.getCheckinStatuses()).thenReturn(Map.of(
            "candidate", CheckinStatus.NOT_CONFIGURED,
            "available", CheckinStatus.NOT_CONFIGURED,
            "disabled", CheckinStatus.NOT_CONFIGURED,
            "late", CheckinStatus.LATE,
            "missed", CheckinStatus.MISSED));
        when(healthMonitor.getStaleSince("missed"))
            .thenReturn(Optional.of(Instant.parse("2026-08-14T11:00:00Z")));

        ActiveAlertsSnapshot snapshot = service.snapshot();

        assertEquals(1, snapshot.alerts().size());
        assertEquals("device:missed:missed-checkin", snapshot.alerts().getFirst().alertId());
        assertEquals("WARN", snapshot.alerts().getFirst().severity());
        assertEquals(1L, snapshot.counts().get("TOTAL"));
        assertEquals(0L, snapshot.counts().get("CRITICAL"));
    }

    @Test
    void currentAlarmIsCriticalAndOutranksMissedCheckinForSameDevice() {
        DeviceStatus alarm = status("leak", "ALARM");
        when(registry.visible()).thenReturn(List.of(alarm));
        configure("leak", DeviceLifecycleState.ASSIGNED, true);
        when(healthMonitor.getCheckinStatuses()).thenReturn(Map.of("leak", CheckinStatus.MISSED));

        ActiveAlertsSnapshot snapshot = service.snapshot();

        assertEquals(1, snapshot.alerts().size(), "one device must not be double-counted");
        assertEquals("DEVICE_ALARM", snapshot.alerts().getFirst().condition());
        assertEquals("CRITICAL", snapshot.alerts().getFirst().severity());
        assertEquals(1L, snapshot.counts().get("CRITICAL"));
        assertEquals(0L, snapshot.counts().get("WARN"));
    }

    @Test
    void noCurrentEvidenceReturnsAnHonestEmptySnapshot() {
        DeviceStatus online = status("online", "ONLINE");
        when(registry.visible()).thenReturn(List.of(online));
        configure("online", DeviceLifecycleState.ASSIGNED, true);
        when(healthMonitor.getCheckinStatuses()).thenReturn(Map.of("online", CheckinStatus.ON_SCHEDULE));

        ActiveAlertsSnapshot snapshot = service.snapshot();

        assertTrue(snapshot.alerts().isEmpty());
        assertEquals(0L, snapshot.counts().get("TOTAL"));
    }
}
