package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.CheckinStatus;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
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
 *   - Zigbee mains devices: stale after 10 min
 *   - Zigbee battery devices: late after 26 hours (sleepy sensors commonly report daily)
 *   - Cameras/RTSP:      stale after 5 min  (Frigate sends motion events)
 *   - HA polled devices: stale after 15 min (HA bridge polls every ~30s)
 *   - Unknown:           stale after 30 min
 *
 * "Stale" is not the same claim as "actually unreachable" — a device that
 * simply hasn't pushed an update yet isn't necessarily broken, and telling
 * users "OFFLINE" the instant one interval is missed was actively
 * misleading (2026-08-08 user report). {@link CheckinStatus} is the
 * user-facing axis for this: ON_SCHEDULE → LATE (past its interval, not yet
 * confirmed dead — a grace tier, `DeviceStatus.state` is untouched here) →
 * MISSED (past a longer grace multiple; for `ha_rest` devices only after an
 * active poll also failed — `DeviceRegistry.activeFetch()` — everything
 * else has no request/response capability to probe, so time alone decides).
 * `DeviceStatus.state` itself still only flips to OFFLINE at the MISSED
 * tier, same trigger point as before this change, so nothing that reads
 * `state` needs to change; CheckinStatus is additive.
 *
 * System health summary is exposed via getSystemHealth() for GET /api/system/health.
 */
@Component
public class DeviceHealthMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeviceHealthMonitor.class);

    // deviceId → when the device first went off-schedule (null if healthy)
    private final Map<String, Instant> staleSince = new ConcurrentHashMap<>();
    // deviceId → last state before going offline
    private final Map<String, String> lastKnownState = new ConcurrentHashMap<>();
    // deviceId → current checkin status, recomputed every cycle
    private final Map<String, CheckinStatus> checkinStatuses = new ConcurrentHashMap<>();

    // Reconnect backoff per-device: attempt count
    private final Map<String, Integer> reconnectAttempts = new ConcurrentHashMap<>();

    private final DeviceRegistry registry;
    private final Zigbee2MqttAdapter z2mAdapter;

    private static final Duration STALE_ZIGBEE  = Duration.ofMinutes(10);
    private static final Duration STALE_ZIGBEE_BATTERY = Duration.ofHours(26);
    private static final Duration STALE_CAMERA  = Duration.ofMinutes(5);
    private static final Duration STALE_HA      = Duration.ofMinutes(15);
    private static final Duration STALE_DEFAULT = Duration.ofMinutes(30);

    /** How many multiples of the stale threshold a device gets in the LATE grace tier before MISSED. */
    private static final int MISSED_MULTIPLIER = 3;

    public DeviceHealthMonitor(DeviceRegistry registry, Zigbee2MqttAdapter z2mAdapter) {
        this.registry = registry;
        this.z2mAdapter = z2mAdapter;
    }

    /** Runs every 60 seconds. Checks all registered devices for staleness. */
    @Scheduled(fixedDelay = 60_000)
    public void checkHealth() {
        Instant now = Instant.now();
        for (DeviceStatus status : registry.all().stream()
            .filter(device -> !registry.lifecycleState(device.deviceId()).isPreviouslyExposed())
            .toList()) {
            String id = status.deviceId();
            Optional<DeviceDescriptor> descriptor = registry.descriptor(id);

            if (descriptor.isPresent() && (!descriptor.get().enabled()
                || !registry.lifecycleState(id).allowsActiveUse())) {
                checkinStatuses.put(id, CheckinStatus.NOT_CONFIGURED);
                continue;
            }

            Duration staleThreshold = staleThresholdFor(id, status);
            Duration sinceLastSeen = Duration.between(status.lastSeen(), now);

            if (sinceLastSeen.compareTo(staleThreshold) <= 0) {
                checkinStatuses.put(id, CheckinStatus.ON_SCHEDULE);
                recoverIfNeeded(id, "on schedule again");
                continue;
            }

            if (tryActiveRecovery(id, descriptor, now)) {
                checkinStatuses.put(id, CheckinStatus.ON_SCHEDULE);
                recoverIfNeeded(id, "active check confirmed it's actually reachable");
                continue;
            }

            Duration missedThreshold = staleThreshold.multipliedBy(MISSED_MULTIPLIER);
            CheckinStatus classified = classify(sinceLastSeen, staleThreshold, missedThreshold, true);
            checkinStatuses.put(id, classified);

            if (!staleSince.containsKey(id)) {
                staleSince.put(id, now);
                lastKnownState.put(id, status.state());
                log.warn("Device {} went off-schedule after {} (last seen {})", id, sinceLastSeen, status.lastSeen());
            }

            if (classified == CheckinStatus.MISSED && !"OFFLINE".equals(status.state())) {
                Map<String, Object> attrs = new LinkedHashMap<>(status.attributes());
                attrs.put("staleSince", staleSince.get(id).toString());
                attrs.put("lastKnownState", lastKnownState.get(id));
                registry.update(new DeviceStatus(
                    id, status.type(), status.name(), "OFFLINE",
                    status.lastSeen(), attrs, status.location()));
            }
            scheduleReconnect(id, status);
        }
    }

    /** Pure classification: given how late a device is, which tier is it in. Package-private for tests. */
    static CheckinStatus classify(Duration sinceLastSeen, Duration staleThreshold, Duration missedThreshold, boolean enabled) {
        if (!enabled) return CheckinStatus.NOT_CONFIGURED;
        if (sinceLastSeen.compareTo(staleThreshold) <= 0) return CheckinStatus.ON_SCHEDULE;
        if (sinceLastSeen.compareTo(missedThreshold) <= 0) return CheckinStatus.LATE;
        return CheckinStatus.MISSED;
    }

    /**
     * For ha_rest devices only: actively poll HA directly rather than wait for the
     * next passive update. MQTT/RTSP adapters don't support request/response, so
     * this is always a no-op (returns false) for them — time-based tiering is all
     * that's available there.
     */
    private boolean tryActiveRecovery(String id, Optional<DeviceDescriptor> descriptor, Instant now) {
        String adapterType = descriptor.map(DeviceDescriptor::protocolAdapter).orElse("unknown");
        if (!"ha_rest".equals(adapterType)) return false;

        Optional<DeviceStatus> live = registry.activeFetch(id);
        if (live.isEmpty()) return false;

        DeviceStatus fresh = live.get();
        registry.update(new DeviceStatus(
            id, fresh.type(), fresh.name(), fresh.state(), now, fresh.attributes(), fresh.location()));
        return true;
    }

    private void recoverIfNeeded(String id, String reason) {
        if (staleSince.containsKey(id)) {
            log.info("Device {} recovered — {}", id, reason);
            staleSince.remove(id);
            lastKnownState.remove(id);
            reconnectAttempts.remove(id);
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
            log.info("Z2M device {} off-schedule, reconnect attempt #{} — waiting for Z2M bridge to push state",
                deviceId, attempts);
        }
    }

    private Duration staleThresholdFor(String deviceId, DeviceStatus status) {
        Object configuredMinutes = status.attributes().get("expectedCheckinMinutes");
        if (configuredMinutes instanceof Number minutes && minutes.longValue() > 0) {
            return Duration.ofMinutes(minutes.longValue());
        }
        if (deviceId.startsWith("z2m-")) {
            Object powerSource = status.attributes().get("powerSource");
            boolean battery = "battery".equalsIgnoreCase(String.valueOf(powerSource))
                || status.attributes().containsKey("battery") || status.attributes().containsKey("battery_low");
            return battery ? STALE_ZIGBEE_BATTERY : STALE_ZIGBEE;
        }
        return switch (status.type()) {
            case CAMERA -> STALE_CAMERA;
            case THERMOSTAT, LOCK, SMOKE_ALARM, CO_ALARM,
                 DISHWASHER, WASHING_MACHINE, DRYER, POWER_METER -> STALE_HA;
            default -> STALE_DEFAULT;
        };
    }

    /** Returns a structured health summary for GET /api/system/health. */
    public Map<String, Object> getSystemHealth() {
        List<DeviceStatus> all = registry.inScope();
        Set<String> inScopeIds = all.stream().map(DeviceStatus::deviceId).collect(java.util.stream.Collectors.toSet());
        long online  = all.stream().filter(d -> "ONLINE".equals(d.state())).count();
        long offline = all.stream().filter(d -> "OFFLINE".equals(d.state())).count();
        long alarm   = all.stream().filter(d -> "ALARM".equals(d.state())).count();
        long unknown = all.stream().filter(d -> "UNKNOWN".equals(d.state())).count();

        List<Map<String, Object>> staleDevices = staleSince.entrySet().stream()
            .filter(e -> inScopeIds.contains(e.getKey()))
            .map(e -> Map.<String, Object>of(
                "deviceId", e.getKey(),
                "staleSince", e.getValue().toString(),
                "lastKnownState", lastKnownState.getOrDefault(e.getKey(), "UNKNOWN"),
                "checkinStatus", checkinStatuses.getOrDefault(e.getKey(), CheckinStatus.LATE).name()
            ))
            .toList();

        Map<String, Long> checkinCounts = new LinkedHashMap<>();
        for (CheckinStatus s : CheckinStatus.values()) {
            checkinCounts.put(s.name(), checkinStatuses.entrySet().stream()
                .filter(entry -> inScopeIds.contains(entry.getKey()))
                .filter(entry -> entry.getValue() == s).count());
        }

        return Map.of(
            "total", all.size(),
            "online", online,
            "offline", offline,
            "alarm", alarm,
            "unknown", unknown,
            "zigbeeBridge", z2mAdapter.getBridgeState(),
            "staleDevices", staleDevices,
            "checkinStatusCounts", checkinCounts,
            "checkedAt", Instant.now().toString()
        );
    }

    /** Per-device checkin status, keyed by deviceId. Devices not yet checked this cycle are omitted. */
    public Map<String, CheckinStatus> getCheckinStatuses() {
        Map<String, CheckinStatus> visible = new LinkedHashMap<>();
        checkinStatuses.forEach((deviceId, status) -> {
            if (!registry.lifecycleState(deviceId).isPreviouslyExposed()) visible.put(deviceId, status);
        });
        return Map.copyOf(visible);
    }

    /** User-facing lifecycle explanation; avoids a status badge with no reason or next step. */
    public Map<String, Map<String, Object>> getCheckinDetails() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        Instant now = Instant.now();
        for (DeviceStatus status : registry.all().stream()
            .filter(device -> !registry.lifecycleState(device.deviceId()).isPreviouslyExposed())
            .toList()) {
            Duration expected = staleThresholdFor(status.deviceId(), status);
            CheckinStatus checkin = checkinStatuses.getOrDefault(status.deviceId(),
                registry.descriptor(status.deviceId()).filter(DeviceDescriptor::enabled).isPresent()
                    ? CheckinStatus.ON_SCHEDULE : CheckinStatus.NOT_CONFIGURED);
            boolean battery = expected.equals(STALE_ZIGBEE_BATTERY)
                || "battery".equalsIgnoreCase(String.valueOf(status.attributes().get("powerSource")));
            String reason = switch (checkin) {
                case NOT_CONFIGURED -> switch (registry.lifecycleState(status.deviceId())) {
                    case CANDIDATE -> "Discovered as a candidate; make an explicit review decision before health monitoring starts.";
                    case AVAILABLE -> "Accepted and available, but not assigned; save an actual configuration change to start monitoring.";
                    case ASSIGNED -> "Assigned but disabled; enable it to start health monitoring.";
                    case DEFERRED, IGNORED -> "Previously exposed devices are not actively monitored.";
                };
                case ON_SCHEDULE -> battery
                    ? "Battery device is within its expected reporting window; it may sleep between reports."
                    : "Device checked in within its expected reporting window.";
                case LATE -> "Expected report has not arrived yet; this does not prove the device is offline.";
                case MISSED -> "No report arrived during the full grace window and the device is treated as not responding.";
            };
            out.put(status.deviceId(), Map.of(
                "status", checkin.name(),
                "expectedMinutes", expected.toMinutes(),
                "missedAfterMinutes", expected.multipliedBy(MISSED_MULTIPLIER).toMinutes(),
                "minutesSinceLastSeen", Math.max(0, Duration.between(status.lastSeen(), now).toMinutes()),
                "reason", reason));
        }
        return out;
    }

    public Optional<Instant> getStaleSince(String deviceId) {
        return Optional.ofNullable(staleSince.get(deviceId));
    }
}
