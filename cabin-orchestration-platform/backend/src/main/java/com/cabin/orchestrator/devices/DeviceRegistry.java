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
    private final DeviceRepository deviceRepository;
    // Device configuration arrives on HTTP threads while discovery arrives on
    // MQTT callback and scheduler threads. Both descriptor and status must be
    // decided and written under the same per-device lock; two independent
    // ConcurrentHashMaps do not make a cross-map transaction atomic.
    private final Map<String, Object> deviceLocks = new ConcurrentHashMap<>();

    @Autowired
    public DeviceRegistry(List<ProtocolAdapter> adapterList, DeviceLifecycleStore lifecycleStore,
                           DeviceRepository deviceRepository) {
        this.lifecycleStore = lifecycleStore;
        this.deviceRepository = deviceRepository;
        adapterList.forEach(a -> adapters.put(a.adapterType(), a));
        seedDefaults();
        restorePersistedDevices();
    }

    /** Convenience constructor for isolated unit tests that do care about lifecycle persistence. */
    public DeviceRegistry(List<ProtocolAdapter> adapterList, DeviceLifecycleStore lifecycleStore) {
        this(adapterList, lifecycleStore, noOpDeviceRepository());
    }

    /** Convenience constructor for isolated unit tests. */
    public DeviceRegistry(List<ProtocolAdapter> adapterList) {
        this(adapterList, new DeviceLifecycleStore() {
            @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.of(); }
            @Override public void save(DeviceLifecycleRecord record) { }
            @Override public void delete(String deviceId) { }
        });
    }

    /** Empty-string values ("" -- an adapter's own JSON default for an absent field) count as absent, not a real fact worth persisting. */
    private static String stringAttr(Map<String, Object> attrs, String key) {
        Object value = attrs.get(key);
        if (!(value instanceof String s) || s.isBlank()) return null;
        return s;
    }

    private static DeviceRepository noOpDeviceRepository() {
        return new DeviceRepository() {
            @Override public void upsert(String deviceId, DeviceMetadata metadata) { }
            @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.empty(); }
            @Override public Map<String, DeviceMetadata> loadAll() { return Map.of(); }
        };
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
                    descriptor, statuses.get(deviceId), record.lifecycleState(), record.extraAttributes(), record.updatedAt()));
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
                // A nudge for a device new to this process's memory. Passive
                // discovery deliberately never persists (see the comment
                // below on descriptors/statuses) -- an undecided candidate
                // does not survive a restart, so firstSeen legitimately
                // goes true again on the next republish after a deploy.
                // That's acceptable here: it re-surfaces a still-undecided
                // device rather than losing the prompt permanently. Cleared
                // within this process's lifetime by statusWithLifecycle
                // once the device leaves CANDIDATE, or explicitly by
                // DeviceDiscoveryController when a lookup is run for it.
                if (firstSeen && lifecycle == DeviceLifecycleState.CANDIDATE) {
                    attrs.put("discoverySuggested", true);
                }
                putLifecycleAttributes(attrs, lifecycle, merged.enabled());
                attrs.put("source", merged.protocolAdapter());
                attrs.put("capabilities", merged.capabilities().stream().map(Enum::name).sorted().toList());
                return new DeviceStatus(id, merged.type(), merged.name(), existing == null ? "UNKNOWN" : existing.state(),
                    existing == null ? Instant.now() : existing.lastSeen(), attrs, merged.location());
            });
            // A no-op for a still-undecided candidate with no `device` row
            // yet (see the comment above on why passive discovery doesn't
            // persist) -- upsert() only updates an existing row. Manufacturer/
            // model ride whatever key each adapter already populates
            // ("vendor"/"model" for Zigbee2MqttAdapter); other adapters that
            // don't populate them just leave metadata unchanged, not nulled.
            if (discoveryAttributes != null) {
                String manufacturer = stringAttr(discoveryAttributes, "vendor");
                String model = stringAttr(discoveryAttributes, "model");
                if (manufacturer != null || model != null) {
                    deviceRepository.upsert(desc.deviceId(), new DeviceMetadata(
                        manufacturer, model, null, Instant.now(), null, null, "system", null, 0));
                }
            }
            return firstSeen;
        }
    }

    /**
     * Clear the one-time "new device -- want to look it up?" nudge once a
     * person has acted on it, whether by explicitly running a discovery
     * lookup or by deciding the candidate outright. Doesn't touch
     * lifecycle, descriptor, or any persisted state -- attrs.
     */
    public void dismissDiscoverySuggestion(String deviceId) {
        synchronized (lockFor(deviceId)) {
            DeviceStatus existing = statuses.get(deviceId);
            if (existing == null || !existing.attributes().containsKey("discoverySuggested")) return;
            Map<String, Object> attrs = new LinkedHashMap<>(existing.attributes());
            attrs.remove("discoverySuggested");
            statuses.put(deviceId, new DeviceStatus(existing.deviceId(), existing.type(), existing.name(),
                existing.state(), existing.lastSeen(), attrs, existing.location()));
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
     * be reviewed and renamed without implying acceptance. Explicitly saving a
     * candidate as enabled is itself a person-authored decision to use it, so
     * that write atomically accepts and assigns the device. The separate ACCEPT
     * lifecycle action remains useful when the person only wants the device in
     * scope as AVAILABLE without assigning or enabling it yet.
     */
    public ConfigurationSaveResult saveConfiguration(String deviceId, String name, boolean enabled) {
        return saveConfiguration(deviceId, name, enabled, Map.of());
    }

    /**
     * extraAttributes (added 2026-08-18, UX rework's room field) rides the
     * same DeviceLifecycleRecord slot saveConfiguration already persists
     * name/enabled through -- see DeviceLifecycleRecord's own comment for
     * why this wasn't added as a real DeviceDescriptor field instead.
     * Always treated as a real change when non-empty: unlike name/enabled,
     * the registry has no cheap prior value to diff extraAttributes
     * against here (it's not part of `existing`, a DeviceDescriptor), so
     * a caller explicitly passing extraAttributes is trusted to mean "save
     * this" rather than optimized away as a possible no-op.
     */
    public ConfigurationSaveResult saveConfiguration(String deviceId, String name, boolean enabled,
                                                       Map<String, Object> extraAttributes) {
        synchronized (lockFor(deviceId)) {
            DeviceDescriptor existing = descriptors.get(deviceId);
            if (existing == null) throw new NoSuchElementException("Device not found: " + deviceId);
            DeviceLifecycleState current = lifecycleState(deviceId);
            if (current.isPreviouslyExposed()) {
                throw new IllegalStateException("Review this previously exposed device before configuring it");
            }
            String nextName = name == null ? existing.name() : name.trim();
            if (nextName.isBlank()) throw new IllegalArgumentException("Device name cannot be blank");
            if (extraAttributes.containsKey("parentDeviceId")) {
                validateParentDeviceId(deviceId, existing.location(), extraAttributes.get("parentDeviceId"));
            }
            boolean changed = !Objects.equals(existing.name(), nextName) || existing.enabled() != enabled
                || !extraAttributes.isEmpty();
            if (!changed) return new ConfigurationSaveResult(false, current, existing);

            DeviceDescriptor updated = new DeviceDescriptor(
                existing.deviceId(), nextName, existing.type(), existing.capabilities(),
                existing.protocolAdapter(), existing.connectionString(), enabled, existing.location());
            DeviceLifecycleState target = current == DeviceLifecycleState.AVAILABLE
                || (current == DeviceLifecycleState.CANDIDATE && !existing.enabled() && enabled)
                ? DeviceLifecycleState.ASSIGNED : current;
            DeviceLifecycleRecord record = new DeviceLifecycleRecord(updated, target, true, extraAttributes);
            lifecycleStore.save(record);
            applyPersistedRecord(record);
            return new ConfigurationSaveResult(true, target, updated);
        }
    }

    /**
     * Real invariants for the device→services hierarchy's MVP
     * parentDeviceId link (2026-08-25): a blank value clears the parent
     * and is always valid. A non-blank value must name a real, existing
     * device, at the same location, that isn't this device itself and
     * doesn't already lead back to this device through its own parent
     * chain. Deliberately checked here rather than left to the frontend
     * -- extraAttributes is otherwise opaque to this class, but a
     * dangling or cyclic parent reference is a real data-integrity bug,
     * not a cosmetic one.
     */
    private void validateParentDeviceId(String deviceId, String location, Object parentDeviceIdRaw) {
        String parentDeviceId = parentDeviceIdRaw == null ? "" : String.valueOf(parentDeviceIdRaw).trim();
        if (parentDeviceId.isEmpty()) return;
        if (parentDeviceId.equals(deviceId)) {
            throw new IllegalArgumentException("A device cannot be its own parent");
        }
        DeviceDescriptor parent = descriptors.get(parentDeviceId);
        if (parent == null) {
            throw new IllegalArgumentException("Parent device not found: " + parentDeviceId);
        }
        if (!Objects.equals(parent.location(), location)) {
            throw new IllegalArgumentException("Parent device must be at the same location");
        }
        Set<String> visited = new HashSet<>();
        String current = parentDeviceId;
        while (current != null && visited.add(current)) {
            if (current.equals(deviceId)) {
                throw new IllegalArgumentException("Setting this parent would create a cycle");
            }
            DeviceStatus currentStatus = statuses.get(current);
            Object next = currentStatus == null ? null : currentStatus.attributes().get("parentDeviceId");
            current = next == null || String.valueOf(next).isBlank() ? null : String.valueOf(next).trim();
        }
    }

    /**
     * Apply a person-approved subset of freshly re-discovered fields onto an
     * already-configured device -- the "replace device settings with new
     * definitions" step of a self-discovery re-sync. Unlike
     * saveConfiguration() (name/enabled only, from the manual edit form),
     * this can also replace type/capabilities/location, because the person
     * has explicitly reviewed and approved a discovery result field by
     * field rather than typing a value. Only touches the fields named in
     * selectedFields; enabled state is never touched by this path. A
     * CANDIDATE must go through applyLifecycleAction(ACCEPT) +
     * saveConfiguration() instead (that's what discovery/apply's mode=new
     * does) -- this method is specifically the mode=replace path for a
     * device that's already AVAILABLE or ASSIGNED.
     */
    public ConfigurationSaveResult replaceConfiguration(String deviceId, DeviceDescriptor proposed,
                                                          Set<String> selectedFields) {
        synchronized (lockFor(deviceId)) {
            DeviceDescriptor existing = descriptors.get(deviceId);
            if (existing == null) throw new NoSuchElementException("Device not found: " + deviceId);
            DeviceLifecycleState current = lifecycleState(deviceId);
            if (current == DeviceLifecycleState.CANDIDATE) {
                throw new IllegalStateException("Accept this candidate before replacing its configuration");
            }
            if (current.isPreviouslyExposed()) {
                throw new IllegalStateException("Review this previously exposed device before configuring it");
            }

            String nextName = selectedFields.contains("name") ? proposed.name() : existing.name();
            if (nextName == null || nextName.isBlank()) throw new IllegalArgumentException("Device name cannot be blank");

            DeviceDescriptor updated = new DeviceDescriptor(
                existing.deviceId(),
                nextName,
                selectedFields.contains("type") ? proposed.type() : existing.type(),
                selectedFields.contains("capabilities") ? proposed.capabilities() : existing.capabilities(),
                existing.protocolAdapter(),   // source-owned, not replaceable through this path
                existing.connectionString(),  // source-owned, not replaceable through this path
                existing.enabled(),           // this path never changes enabled state
                selectedFields.contains("location") ? proposed.location() : existing.location());

            if (updated.equals(existing)) return new ConfigurationSaveResult(false, current, existing);

            DeviceLifecycleRecord record = new DeviceLifecycleRecord(updated, current, true);
            lifecycleStore.save(record);
            applyPersistedRecord(record);
            return new ConfigurationSaveResult(true, current, updated);
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
            descriptor, statuses.get(descriptor.deviceId()), record.lifecycleState(), record.extraAttributes(), record.updatedAt()));
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
            .map(this::withOntologyMetadata)
            .toList();
    }

    public List<DeviceStatus> candidates() {
        return statuses.values().stream()
            .filter(status -> lifecycleState(status.deviceId()) == DeviceLifecycleState.CANDIDATE)
            .map(this::withOntologyMetadata)
            .toList();
    }

    public List<DeviceStatus> previouslyExposed() {
        return statuses.values().stream()
            .filter(status -> lifecycleState(status.deviceId()).isPreviouslyExposed())
            .map(this::withOntologyMetadata)
            .toList();
    }

    public List<DeviceStatus> byLocation(String location) {
        return statuses.values().stream()
            .filter(s -> location.equals(s.location()))
            .map(this::withOntologyMetadata)
            .toList();
    }

    public DeviceStatus get(String deviceId) {
        return withOntologyMetadata(statuses.get(deviceId));
    }

    /**
     * Attaches capabilities/category to every DeviceStatus this registry
     * ever returns to a caller -- added 2026-08-19 to close a real gap: a
     * device's DeviceCapability set lives only on DeviceDescriptor and was
     * never serialized into DeviceStatus, the one shape the frontend
     * actually receives (App.jsx's own comment on WORKFLOW_BY_TYPE says so
     * explicitly), so the UI built a second, hand-maintained, drifting
     * shadow of this exact taxonomy instead of ever seeing the real thing.
     *
     * Computed fresh on every read rather than cached in `attrs` at write
     * time (registerCandidate()/statusWithLifecycle() already stuff a
     * capabilities snapshot into attrs at a few specific mutation points,
     * left as-is, harmless redundancy) -- capabilities/category are a
     * static fact of the descriptor/type, fixed at registration, so
     * deriving them here is both simpler and can never go stale the way a
     * cached copy could if some future write path forgot to refresh it.
     */
    private DeviceStatus withOntologyMetadata(DeviceStatus status) {
        if (status == null) return null;
        Map<String, Object> attrs = new LinkedHashMap<>(status.attributes());
        attrs.put("category", status.type().category().name());
        attrs.put("reportsFields", status.type().telemetryFields().stream().sorted().toList());
        DeviceDescriptor descriptor = descriptors.get(status.deviceId());
        if (descriptor != null) {
            attrs.put("capabilities", descriptor.capabilities().stream().map(Enum::name).sorted().toList());
        }
        return new DeviceStatus(status.deviceId(), status.type(), status.name(), status.state(),
            status.lastSeen(), attrs, status.location());
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
        return statusWithLifecycle(descriptor, existing, lifecycle, Map.of(), null);
    }

    /**
     * extraAttributes (added 2026-08-18) layers DeviceLifecycleRecord's
     * durable per-device facts (room, and any future one riding the same
     * slot) onto the live runtime status -- called from both
     * restorePersistedDevices() (so a set room survives a restart) and
     * applyPersistedRecord() (so it's visible immediately on save, not
     * just after the next restart).
     *
     * lifecycleUpdatedAt (Part D, 2026-09-02) is set directly rather than
     * folded into extraAttributes -- see DeviceLifecycleRecord's own doc
     * for why it can't safely ride that channel. A null value (candidate/
     * never-persisted devices) simply omits the attribute rather than
     * writing a misleading "now."
     */
    private DeviceStatus statusWithLifecycle(DeviceDescriptor descriptor,
                                             DeviceStatus existing,
                                             DeviceLifecycleState lifecycle,
                                             Map<String, Object> extraAttributes,
                                             Instant lifecycleUpdatedAt) {
        Map<String, Object> attrs = new LinkedHashMap<>(existing == null ? Map.of() : existing.attributes());
        if (lifecycle != DeviceLifecycleState.CANDIDATE) attrs.remove("discoverySuggested");
        putLifecycleAttributes(attrs, lifecycle, descriptor.enabled());
        attrs.put("source", descriptor.protocolAdapter());
        attrs.put("capabilities", descriptor.capabilities().stream().map(Enum::name).sorted().toList());
        attrs.putAll(extraAttributes);
        if (lifecycleUpdatedAt != null) attrs.put("lifecycleUpdatedAt", lifecycleUpdatedAt.toString());
        else attrs.remove("lifecycleUpdatedAt");
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
