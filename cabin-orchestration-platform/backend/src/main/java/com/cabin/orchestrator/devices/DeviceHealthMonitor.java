package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors device health and marks devices stale when they stop reporting.
 *
 * Stale thresholds by device type:
 *   - Zigbee sensors:    stale after 10 min (Z2M state pushes are event-driven)
 *   - Cameras/RTSP:      stale after 5 min  (Frigate sends motion events)
 *   - HA polled devices: stale after 15 min (HA bridge polls every ~30s)
 *   - Unknown:           stale after 30 min
 *
 * Devices that become stale are updated to state=OFFLINE in the registry.
 * The last known good state is preserved in staleSince + lastKnownState attrs.
 *
 * System health summary is exposed via getSystemHealth() for GET /api/system/health.
 */
@Component
public class DeviceHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthMonitor.class);

    // deviceId → when the device first went stale (null if healthy)
    private final Map<String, Instant> staleSince = new ConcurrentHashMap<>();
    // deviceId → last state before going offline
    private final Map<String, String> lastKnownState = new ConcurrentHashMap<>();

    // Reconnect backoff per-device: attempt count
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final Zigbee2MqttAdapter z2mAdapter;

    private static final Duration STALE_ZIGBEE  = Duration.ofMinutes(10);
    private static final Duration STALE_CAMERA  = Duration.ofMinutes(5);
    private static final Duration STALE_HA      = Duration.ofMinutes(15);
    private static final Duration STALE_DEFAULT = Duration.ofMinutes(30);

    public DeviceHealthMonitor(DeviceRegistry registry, Zigbee2MqttAdapter z2mAdapter) {
        this.registry = registry;
        this.z2mAdapter = z2mAdapter;
    }

    /** Runs every 60 seconds. Checks all registered devices for staleness. */
    @Scheduled(fixedDelay = 60_000)
    public void checkHealth() {
        Instant now = Instant.now();
        for (DeviceStatus status : registry.all()) {
            String id = status.deviceId();
            Duration staleThreshold = staleThresholdFor(id, status);
            Duration sinceLastSeen = Duration.between(status.lastSeen(), now);

            if (sinceLastSeen.compareTo(staleThreshold) > 0) {
                // Device has gone stale
                if (!staleSince.containsKey(id)) {
                    staleSince.put(id, now);
                    lastKnownState.put(id, status.state());
                    log.warn("Device {} went stale after {} (last seen {})", id, sinceLastSeen, status.lastSeen());
                }
                if (!"OFFLINE".equals(status.state())) {
                    Map<String, Object> attrs = new LinkedHashMap<>(status.attributes());
                    attrs.put("staleSince", staleSince.get(id).toString());
                    attrs.put("lastKnownState", lastKnownState.get(id));
                    registry.update(new DeviceStatus(
                        id, status.type(), status.name(), "OFFLINE",
                        status.lastSeen(), attrs, status.location()));
                }
                scheduleReconnect(id, status);
            } else {
                // Device recovered
                if (staleSince.containsKey(id)) {
                    log.info("Device {} recovered", id);
                    staleSince.remove(id);
                    lastKnownState.remove(id);
                    reconnectAttempts.remove(id);
                }
            }
        }
    }

    /**
     * Exponential backoff reconnect for MQTT-based devices.
     * On each stale check cycle: only attempt reconnect on cycles that are
     * a power-of-two multiple of the first-stale time (1min, 2min, 4min, 8min…).
     */
    private void scheduleReconnect(String deviceId, DeviceStatus status) {
        String adapter = registry.descriptor(deviceId)
            .map(d -> d.protocolAdapter())
            .orElse("unknown");
        if (!"mqtt".equals(adapter)) return; // HA/RTSP reconnect is handled by their own adapters

        int attempts = reconnectAttempts.merge(deviceId, 1, Integer::sum);
        // Only log/act on power-of-two attempts to avoid log spam
        if (attempts == 1 || (attempts & (attempts - 1)) == 0) {
            log.info("Z2M device {} offline, reconnect attempt #{} — waiting for Z2M bridge to push state",
                deviceId, attempts);
        }
    }

    private Duration staleThresholdFor(String deviceId, DeviceStatus status) {
        if (deviceId.startsWith("z2m-")) return STALE_ZIGBEE;
        return switch (status.type()) {
            case CAMERA -> STALE_CAMERA;
            case THERMOSTAT, LOCK, SMOKE_ALARM, CO_ALARM,
                 DISHWASHER, WASHING_MACHINE, DRYER, POWER_METER -> STALE_HA;
            default -> STALE_DEFAULT;
        };
    }

    /** Returns a structured health summary for GET /api/system/health. */
    public Map<String, Object> getSystemHealth() {
        List<DeviceStatus> all = registry.all();
        long online  = all.stream().filter(d -> "ONLINE".equals(d.state())).count();
        long offline = all.stream().filter(d -> "OFFLINE".equals(d.state())).count();
        long alarm   = all.stream().filter(d -> "ALARM".equals(d.state())).count();
        long unknown = all.stream().filter(d -> "UNKNOWN".equals(d.state())).count();

        List<Map<String, Object>> staleDevices = staleSince.entrySet().stream()
            .map(e -> Map.<String, Object>of(
                "deviceId", e.getKey(),
                "staleSince", e.getValue().toString(),
                "lastKnownState", lastKnownState.getOrDefault(e.getKey(), "UNKNOWN")
            ))
            .toList();

        return Map.of(
            "total", all.size(),
            "online", online,
            "offline", offline,
            "alarm", alarm,
            "unknown", unknown,
            "zigbeeBridge", z2mAdapter.getBridgeState(),
            "staleDevices", staleDevices,
            "checkedAt", Instant.now().toString()
        );
    }

    public Optional<Instant> getStaleSince(String deviceId) {
        return Optional.ofNullable(staleSince.get(deviceId));
    }
}
