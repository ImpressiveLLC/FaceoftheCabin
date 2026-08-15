package com.cabin.orchestrator.alerts;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves truthful, current alert conditions from the two authoritative
 * runtime axes: DeviceStatus.state and CheckinStatus.
 *
 * Visibility is wider than active use by design. Candidates and accepted but
 * unassigned devices remain visible for review, but only an enabled ASSIGNED
 * device is allowed to participate in health alerting. This keeps passive
 * discovery from silently opting a device into monitoring or control.
 */
@Service
public class ActiveAlertService {

    private final DeviceRegistry registry;
    private final DeviceHealthMonitor healthMonitor;

    public ActiveAlertService(DeviceRegistry registry, DeviceHealthMonitor healthMonitor) {
        this.registry = registry;
        this.healthMonitor = healthMonitor;
    }

    public ActiveAlertsSnapshot snapshot() {
        Map<String, CheckinStatus> checkins = healthMonitor.getCheckinStatuses();
        List<ActiveAlert> alerts = new ArrayList<>();

        for (DeviceStatus status : registry.visible()) {
            Optional<DeviceDescriptor> descriptor = registry.descriptor(status.deviceId());
            if (descriptor.isEmpty()
                || !descriptor.get().enabled()
                || !registry.lifecycleState(status.deviceId()).allowsActiveUse()) {
                continue;
            }

            // A reported alarm outranks a missed check-in for the same device.
            // Returning one highest-priority condition avoids double-counting
            // one device in the navigation summary.
            if ("ALARM".equals(status.state())) {
                alerts.add(new ActiveAlert(
                    "device:" + status.deviceId() + ":alarm",
                    status.deviceId(), status.name(), status.location(),
                    "DEVICE_ALARM", "CRITICAL", status.lastSeen(),
                    status.name() + " reports an alarm",
                    "The device's current runtime state is ALARM. Review the source device and its safety workflow."
                ));
                continue;
            }

            if (checkins.get(status.deviceId()) == CheckinStatus.MISSED) {
                Instant evidenceAt = healthMonitor.getStaleSince(status.deviceId()).orElse(status.lastSeen());
                alerts.add(new ActiveAlert(
                    "device:" + status.deviceId() + ":missed-checkin",
                    status.deviceId(), status.name(), status.location(),
                    "MISSED_CHECKIN", "WARN", evidenceAt,
                    status.name() + " missed its check-in window",
                    "No report arrived during the full grace window. The device is assigned and enabled, so it needs review."
                ));
            }
        }

        alerts.sort(Comparator
            .comparingInt((ActiveAlert alert) -> "CRITICAL".equals(alert.severity()) ? 0 : 1)
            .thenComparing(ActiveAlert::sourceName));

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("WARN", alerts.stream().filter(a -> "WARN".equals(a.severity())).count());
        counts.put("CRITICAL", alerts.stream().filter(a -> "CRITICAL".equals(a.severity())).count());
        counts.put("TOTAL", (long) alerts.size());
        return new ActiveAlertsSnapshot(Instant.now(), List.copyOf(alerts), Map.copyOf(counts));
    }
}
