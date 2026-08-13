package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central registry for all devices across all locations.
 * - DeviceDescriptor: static config (capabilities, adapter, connection, location)
 * - DeviceStatus: runtime state (updated by MQTT bridge and HA polling)
 * Dispatches commands to the correct ProtocolAdapter.
 */
@Component
public class DeviceRegistry {

    private final Map<String, DeviceStatus> statuses = new ConcurrentHashMap<>();
    private final Map<String, DeviceDescriptor> descriptors = new ConcurrentHashMap<>();
    private final Map<String, ProtocolAdapter> adapters = new ConcurrentHashMap<>();
    private final Map<String, DeviceLifecycleState> lifecycleStates = new ConcurrentHashMap<>();
    private final Set<String> configurationAsserted = ConcurrentHashMap.newKeySet();
    private final DeviceLifecycleStore lifecycleStore;
    // Device configuration arrives on HTTP threads while discovery arrives on
    // MQTT callback and scheduler threads. Both descriptor and status must be
    // decided and written under the same per-device lock; two independent
    // ConcurrentHashMaps do not make a cross-map transaction atomic.
    private final Map<String, Object> deviceLocks = new ConcurrentHashMap<>();

    @Autowired
    public DeviceRegistry(List<ProtocolAdapter> adapterList, DeviceLifecycleStore lifecycleStore) {
        this.lifecycleStore = lifecycleStore;
        adapterList.forEach(a -> adapters.put(a.adapterType(), a));
        seedDefaults();
        restorePersistedDevices();
    }

    /** Convenience constructor for isolated unit tests. */
    public DeviceRegistry(List<ProtocolAdapter> adapterList) {
        this(adapterList, new DeviceLifecycleStore() {
            @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.of(); }
            @Override public void save(DeviceLifecycleRecord record) { }
            @Override public void delete(String deviceId) { }
        });
    }

    private void seedDefaults() {
        // ── Cabin devices — real paired hardware as of 2026-07-25 ────────────
        // Zigbee devices auto-registered by Zigbee2MqttAdapter (z2m- prefix).
        // Seeds here are non-Zigbee cabin devices only.

        // Future: add cabin thermostat, smoke alarm, cameras when installed
        // Future: add home hub devices when home-hub is deployed

        // ── Home devices — disabled until home-hub deployed ──────────────────

        // Reolink RLC-810A PoE cameras (5× — Frigate, home LAN 192.168.1.20–24)
        registerDescriptor(new DeviceDescriptor(
            "home-cam-front", "Home Front Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.20:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-driveway", "Home Driveway Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.21:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-backyard", "Home Backyard Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.22:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-garage", "Home Garage Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.23:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-cam-side", "Home Side Door Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM, DeviceCapability.PRESENCE),
            "rtsp", "rtsp://admin:{FRIGATE_RTSP_PASSWORD}@192.168.1.24:554/h264Preview_01_main",
            false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lock-front", "Home Front Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_front_door", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lock-back", "Home Back Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL),
            "ha_rest", "lock.home_back_door", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-thermostat-main", "Home Thermostat", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_thermostat", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-smoke-co-main", "Home Smoke/CO Alarm", DeviceType.SMOKE_ALARM,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "ha_rest", "binary_sensor.home_kidde_smoke_co", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-energy-main", "Home Energy Monitor", DeviceType.POWER_METER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.POWER_MONITOR),
            "ha_rest", "sensor.home_emporia_total_power_w", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lg-washer", "Home LG Washer", DeviceType.WASHING_MACHINE,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_washer_state", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-lg-dryer", "Home LG Dryer", DeviceType.DRYER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_lg_dryer_state", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-bosch-dishwasher", "Home Bosch Dishwasher", DeviceType.DISHWASHER,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.APPLIANCE),
            "ha_rest", "sensor.home_bosch_dishwasher_door", false, "home"));

        registerDescriptor(new DeviceDescriptor(
            "home-daikin-hvac", "Home Daikin Aurora HVAC", DeviceType.THERMOSTAT,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND, DeviceCapability.CLIMATE),
            "ha_rest", "climate.home_daikin_aurora", false, "home"));
    }

    private void restorePersistedDevices() {
        lifecycleStore.loadAll().forEach((deviceId, record) -> {
            synchronized (lockFor(deviceId)) {
                DeviceDescriptor descriptor = record.descriptor();
                descriptors.put(deviceId, descriptor);
                lifecycleStates.put(deviceId, record.lifecycleState());
                if (record.configurationAsserted()) configurationAsserted.add(deviceId);
                else configurationAsserted.remove(deviceId);
                statuses.put(deviceId, statusWithLifecycle(
                    descriptor, statuses.get(deviceId), record.lifecycleState()));
            }
        });
    }

    /**
     * Register trusted code-defined configuration (seeds and tests). A new
     * descriptor is assigned; updating an existing candidate records that its
     * configuration was asserted without silently accepting it into scope.
     * Person-authored API writes use saveConfiguration/registerConfiguredDevice
     * below so persistence happens before memory changes.
     */
    public void registerDescriptor(DeviceDescriptor desc) {
        synchronized (lockFor(desc.deviceId())) {
            DeviceLifecycleState lifecycle = lifecycleStates.getOrDefault(
                desc.deviceId(), DeviceLifecycleState.ASSIGNED);
            descriptors.put(desc.deviceId(), desc);
            lifecycleStates.put(desc.deviceId(), lifecycle);
            configurationAsserted.add(desc.deviceId());
            statuses.put(desc.deviceId(), statusWithLifecycle(desc, statuses.get(desc.deviceId()), lifecycle));
        }
    }

    /**
     * Register a device seen by an integration without pretending a person has
     * configured it. Discovery metadata travels with the status so every UI can
     * render the candidate and explain where it came from.
     */
    public boolean registerCandidate(DeviceDescriptor desc, Map<String, Object> discoveryAttributes) {
        synchronized (lockFor(desc.deviceId())) {
            DeviceDescriptor existingDescriptor = descriptors.get(desc.deviceId());
            boolean firstSeen = existingDescriptor == null;

            DeviceLifecycleState lifecycle = lifecycleStates.getOrDefault(
                desc.deviceId(), DeviceLifecycleState.CANDIDATE);
            // Human-authored fields become sticky independently from lifecycle:
            // a renamed candidate remains a candidate until an explicit action,
            // but discovery must not erase the saved name meanwhile.
            boolean configured = existingDescriptor != null
                && (configurationAsserted.contains(desc.deviceId()) || lifecycle != DeviceLifecycleState.CANDIDATE);
            DeviceDescriptor merged = mergeDiscoveryDescriptor(existingDescriptor, desc, configured);
            descriptors.put(desc.deviceId(), merged);
            lifecycleStates.put(desc.deviceId(), lifecycle);

            statuses.compute(desc.deviceId(), (id, existing) -> {
                Map<String, Object> attrs = new LinkedHashMap<>(existing == null ? Map.of() : existing.attributes());
                if (discoveryAttributes != null) {
                    discoveryAttributes.forEach((key, value) -> {
                        if (value == null) attrs.remove(key);
                        else attrs.put(key, value);
                    });
                }
                putLifecycleAttributes(attrs, lifecycle, merged.enabled());
                attrs.put("source", merged.protocolAdapter());
                attrs.put("capabilities", merged.capabilities().stream().map(Enum::name).sorted().toList());
                return new DeviceStatus(id, merged.type(), merged.name(), existing == null ? "UNKNOWN" : existing.state(),
                    existing == null ? Instant.now() : existing.lastSeen(), attrs, merged.location());
            });
            return firstSeen;
        }
    }

    /**
     * Merge ownership for repeated integration discovery.
     *
     * Sticky after configuration: enabled, name, location, type and
     * capabilities. The last two are safety-relevant and require a future
     * explicit re-detect action rather than trusting a degraded discovery
     * snapshot. Adapter/connection remain source-owned and refreshable.
     * A never-configured candidate accepts all corrected discovery fields.
     */
    private DeviceDescriptor mergeDiscoveryDescriptor(DeviceDescriptor existing,
                                                        DeviceDescriptor discovered,
                                                        boolean configured) {
        if (existing == null) return discovered;
        return new DeviceDescriptor(
            discovered.deviceId(),
            configured ? existing.name() : discovered.name(),
            configured ? existing.type() : discovered.type(),
            configured ? existing.capabilities() : discovered.capabilities(),
            discovered.protocolAdapter(),
            discovered.connectionString(),
            configured ? existing.enabled() : discovered.enabled(),
            configured ? existing.location() : discovered.location());
    }

    /** Persist and register a manually-created or fully configured device. */
    public DeviceDescriptor registerConfiguredDevice(DeviceDescriptor descriptor) {
        synchronized (lockFor(descriptor.deviceId())) {
            DeviceLifecycleRecord record = new DeviceLifecycleRecord(
                descriptor, DeviceLifecycleState.ASSIGNED, true);
            lifecycleStore.save(record);
            applyPersistedRecord(record);
            return descriptor;
        }
    }

    /**
     * Persist only an actual configuration change. Candidate configuration can
     * be reviewed and renamed without implying acceptance; enabling a candidate
     * is refused until the person explicitly accepts it as AVAILABLE.
     */
    public ConfigurationSaveResult saveConfiguration(String deviceId, String name, boolean enabled) {
        synchronized (lockFor(deviceId)) {
            DeviceDescriptor existing = descriptors.get(deviceId);
            if (existing == null) throw new NoSuchElementException("Device not found: " + deviceId);
            DeviceLifecycleState current = lifecycleState(deviceId);
            if (current.isPreviouslyExposed()) {
                throw new IllegalStateException("Review this previously exposed device before configuring it");
            }
            if (current == DeviceLifecycleState.CANDIDATE && enabled != existing.enabled()) {
                throw new IllegalStateException("Accept this candidate before enabling it");
            }

            String nextName = name == null ? existing.name() : name.trim();
            if (nextName.isBlank()) throw new IllegalArgumentException("Device name cannot be blank");
            boolean changed = !Objects.equals(existing.name(), nextName) || existing.enabled() != enabled;
            if (!changed) return new ConfigurationSaveResult(false, current, existing);

            DeviceDescriptor updated = new DeviceDescriptor(
                existing.deviceId(), nextName, existing.type(), existing.capabilities(),
                existing.protocolAdapter(), existing.connectionString(), enabled, existing.location());
            DeviceLifecycleState target = current == DeviceLifecycleState.AVAILABLE
                ? DeviceLifecycleState.ASSIGNED : current;
            DeviceLifecycleRecord record = new DeviceLifecycleRecord(updated, target, true);
            lifecycleStore.save(record);
            applyPersistedRecord(record);
            return new ConfigurationSaveResult(true, target, updated);
        }
    }

    /** Apply a persisted, explicit person-authored lifecycle decision. */
    public LifecycleChangeResult applyLifecycleAction(String deviceId, DeviceLifecycleAction action) {
        synchronized (lockFor(deviceId)) {
            DeviceDescriptor existing = descriptors.get(deviceId);
            if (existing == null) throw new NoSuchElementException("Device not found: " + deviceId);
            DeviceLifecycleState current = lifecycleState(deviceId);
            DeviceLifecycleState target = action.targetState();
            if (action == DeviceLifecycleAction.REVIEW && !current.isPreviouslyExposed()) {
                throw new IllegalStateException("Only previously exposed devices need to return to review");
            }
            if (current == target) return new LifecycleChangeResult(false, target);

            // None of the review dispositions grants active use. AVAILABLE is
            // accepted/in-scope but deliberately unassigned and disabled.
            DeviceDescriptor inactive = new DeviceDescriptor(
                existing.deviceId(), existing.name(), existing.type(), existing.capabilities(),
                existing.protocolAdapter(), existing.connectionString(), false, existing.location());
            DeviceLifecycleRecord record = new DeviceLifecycleRecord(
                inactive, target, configurationAsserted.contains(deviceId));
            lifecycleStore.save(record);
            applyPersistedRecord(record);
            return new LifecycleChangeResult(true, target);
        }
    }

    public DeviceLifecycleState lifecycleState(String deviceId) {
        DeviceLifecycleState lifecycle = lifecycleStates.get(deviceId);
        if (lifecycle != null) return lifecycle;
        DeviceStatus status = statuses.get(deviceId);
        return status != null && Boolean.TRUE.equals(status.attributes().get("candidate"))
            ? DeviceLifecycleState.CANDIDATE : DeviceLifecycleState.ASSIGNED;
    }

    public record ConfigurationSaveResult(
        boolean changed, DeviceLifecycleState lifecycleState, DeviceDescriptor descriptor) {}

    public record LifecycleChangeResult(boolean changed, DeviceLifecycleState lifecycleState) {}

    private void applyPersistedRecord(DeviceLifecycleRecord record) {
        DeviceDescriptor descriptor = record.descriptor();
        descriptors.put(descriptor.deviceId(), descriptor);
        lifecycleStates.put(descriptor.deviceId(), record.lifecycleState());
        if (record.configurationAsserted()) configurationAsserted.add(descriptor.deviceId());
        else configurationAsserted.remove(descriptor.deviceId());
        statuses.put(descriptor.deviceId(), statusWithLifecycle(
            descriptor, statuses.get(descriptor.deviceId()), record.lifecycleState()));
    }

    public Optional<DeviceDescriptor> descriptorByConnection(String adapter, String connection, String location) {
        return descriptors.values().stream()
            .filter(d -> Objects.equals(adapter, d.protocolAdapter()))
            .filter(d -> Objects.equals(connection, d.connectionString()))
            .filter(d -> Objects.equals(location, d.location()))
            .findFirst();
    }

    public void register(DeviceStatus status) {
        synchronized (lockFor(status.deviceId())) {
            DeviceLifecycleState lifecycle = lifecycleState(status.deviceId());
            Map<String, Object> attrs = new LinkedHashMap<>(status.attributes());
            DeviceDescriptor descriptor = descriptors.get(status.deviceId());
            boolean enabled = descriptor != null
                ? descriptor.enabled() : Boolean.TRUE.equals(attrs.get("enabled"));
            putLifecycleAttributes(attrs, lifecycle, enabled);
            statuses.put(status.deviceId(), new DeviceStatus(
                status.deviceId(), descriptor == null ? status.type() : descriptor.type(),
                descriptor == null ? status.name() : descriptor.name(), status.state(),
                status.lastSeen(), attrs,
                descriptor == null ? status.location() : descriptor.location()));
        }
    }

    public void update(DeviceStatus status) {
        synchronized (lockFor(status.deviceId())) {
            DeviceStatus current = statuses.get(status.deviceId());
            if (current == null) return; // deleted devices are not resurrected by a stale runtime write
            DeviceDescriptor descriptor = descriptors.get(status.deviceId());
            Map<String, Object> attrs = new LinkedHashMap<>(status.attributes());
            // A runtime update may have been assembled just before a concurrent
            // config save. Keep the lifecycle keys and configured descriptor
            // authoritative when the write finally obtains this device's lock.
            if (current.attributes().containsKey("candidate")) {
                attrs.put("candidate", current.attributes().get("candidate"));
            }
            if (current.attributes().containsKey("deviceLifecycle")) {
                attrs.put("deviceLifecycle", current.attributes().get("deviceLifecycle"));
            }
            if (current.attributes().containsKey("enabled")) {
                attrs.put("enabled", current.attributes().get("enabled"));
            }
            statuses.put(status.deviceId(), new DeviceStatus(
                status.deviceId(), descriptor == null ? status.type() : descriptor.type(),
                descriptor == null ? status.name() : descriptor.name(), status.state(),
                status.lastSeen(), attrs,
                descriptor == null ? status.location() : descriptor.location()));
        }
    }

    public void remove(String deviceId) {
        synchronized (lockFor(deviceId)) {
            lifecycleStore.delete(deviceId);
            statuses.remove(deviceId);
            descriptors.remove(deviceId);
            lifecycleStates.remove(deviceId);
            configurationAsserted.remove(deviceId);
        }
    }

    public List<DeviceStatus> all() {
        return statuses.values().stream().toList();
    }

    public List<DeviceStatus> inScope() {
        return statuses.values().stream()
            .filter(status -> lifecycleState(status.deviceId()).isInScope())
            .toList();
    }

    /**
     * Devices worth showing on read-only monitoring/dashboard surfaces (the
     * main device list, health/checkin counts, My Places) -- everything
     * except what a person explicitly deferred or ignored. A device sitting
     * in CANDIDATE can still be online and reporting real telemetry; hiding
     * it from monitoring just because nobody has reviewed it yet made every
     * already-working device invisible the moment lifecycle review shipped,
     * with no migration path (found 2026-08-13, live user report: health
     * dashboard and My Places went to zero devices even though the real
     * hardware was online). Command dispatch and active polling stay gated
     * by lifecycleState(...).allowsActiveUse() separately -- that's a
     * control decision, not a visibility one, and is deliberately unchanged.
     */
    public List<DeviceStatus> visible() {
        return statuses.values().stream()
            .filter(status -> !lifecycleState(status.deviceId()).isPreviouslyExposed())
            .toList();
    }

    public List<DeviceStatus> candidates() {
        return statuses.values().stream()
            .filter(status -> lifecycleState(status.deviceId()) == DeviceLifecycleState.CANDIDATE)
            .toList();
    }

    public List<DeviceStatus> previouslyExposed() {
        return statuses.values().stream()
            .filter(status -> lifecycleState(status.deviceId()).isPreviouslyExposed())
            .toList();
    }

    public List<DeviceStatus> byLocation(String location) {
        return statuses.values().stream()
            .filter(s -> location.equals(s.location()))
            .toList();
    }

    public DeviceStatus get(String deviceId) {
        return statuses.get(deviceId);
    }

    public Optional<DeviceDescriptor> descriptor(String deviceId) {
        return Optional.ofNullable(descriptors.get(deviceId));
    }

    private Object lockFor(String deviceId) {
        return deviceLocks.computeIfAbsent(deviceId, ignored -> new Object());
    }

    private DeviceStatus statusWithLifecycle(DeviceDescriptor descriptor,
                                             DeviceStatus existing,
                                             DeviceLifecycleState lifecycle) {
        Map<String, Object> attrs = new LinkedHashMap<>(existing == null ? Map.of() : existing.attributes());
        putLifecycleAttributes(attrs, lifecycle, descriptor.enabled());
        attrs.put("source", descriptor.protocolAdapter());
        attrs.put("capabilities", descriptor.capabilities().stream().map(Enum::name).sorted().toList());
        return new DeviceStatus(
            descriptor.deviceId(), descriptor.type(), descriptor.name(),
            existing == null ? "UNKNOWN" : existing.state(),
            existing == null ? Instant.now() : existing.lastSeen(),
            attrs, descriptor.location());
    }

    private static void putLifecycleAttributes(Map<String, Object> attrs,
                                               DeviceLifecycleState lifecycle,
                                               boolean enabled) {
        attrs.put("deviceLifecycle", lifecycle.name());
        // Compatibility for older UI clients; deviceLifecycle is authoritative.
        attrs.put("candidate", lifecycle == DeviceLifecycleState.CANDIDATE);
        attrs.put("enabled", enabled);
    }

    public boolean sendCommand(String deviceId, String command, Object payload) {
        DeviceDescriptor desc = descriptors.get(deviceId);
        if (desc == null || !desc.enabled() || !lifecycleState(deviceId).allowsActiveUse()) return false;
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return false;
        return adapter.sendCommand(desc, command, payload);
    }

    /**
     * Actively poll a device's adapter for its current state, bypassing the
     * passive last-seen cache. Empty means the device didn't answer (or has
     * no descriptor/adapter, or its adapter doesn't support polling — e.g.
     * MQTT devices are push-only and always return empty here).
     */
    public Optional<DeviceStatus> activeFetch(String deviceId) {
        DeviceDescriptor desc = descriptors.get(deviceId);
        if (desc == null || !desc.enabled() || !lifecycleState(deviceId).allowsActiveUse()) {
            return Optional.empty();
        }
        ProtocolAdapter adapter = adapters.get(desc.protocolAdapter());
        if (adapter == null) return Optional.empty();
        return adapter.fetchState(desc);
    }
}
