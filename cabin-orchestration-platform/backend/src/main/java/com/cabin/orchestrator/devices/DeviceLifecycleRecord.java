package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;

import java.time.Instant;
import java.util.Map;

/**
 * Durable, person-authored portion of a registry entry.
 *
 * extraAttributes (added 2026-08-18) is a deliberately generic, open-ended
 * slot for durable per-device facts that don't belong on DeviceDescriptor
 * itself -- adding a real field there touches every one of its ~20
 * construction sites across every adapter. First use is "room" (the UX
 * rework's wired-up grouping dimension -- see DeviceController's PATCH
 * /config), but it's intentionally not room-specific: a later durable
 * per-device setting (e.g. "mute this camera's alerts, keep recording")
 * can reuse the same plumbing without touching JdbcDeviceLifecycleStore
 * again. Lives in DeviceStatus.attributes at runtime, not on the
 * descriptor -- see DeviceRegistry.restorePersistedDevices().
 *
 * updatedAt (added 2026-09-02, Part D) is deliberately NOT part of
 * extraAttributes -- extraAttributes round-trips through
 * JdbcDeviceLifecycleStore.toJson()/loadAll() on every save, so stashing a
 * timestamp there would re-persist a stale snapshot on the next unrelated
 * save instead of tracking the real `device.updated_at` column. Every
 * in-memory construction site describes "the state as of right now," so
 * the 3-/4-arg constructors default it to Instant.now(); only
 * JdbcDeviceLifecycleStore.loadAll() overrides it with the real persisted
 * timestamp read back from the DB.
 */
public record DeviceLifecycleRecord(
    DeviceDescriptor descriptor,
    DeviceLifecycleState lifecycleState,
    boolean configurationAsserted,
    Map<String, Object> extraAttributes,
    Instant updatedAt
) {
    public DeviceLifecycleRecord(DeviceDescriptor descriptor, DeviceLifecycleState lifecycleState,
                                  boolean configurationAsserted, Map<String, Object> extraAttributes) {
        this(descriptor, lifecycleState, configurationAsserted, extraAttributes, Instant.now());
    }

    public DeviceLifecycleRecord(DeviceDescriptor descriptor, DeviceLifecycleState lifecycleState,
                                  boolean configurationAsserted) {
        this(descriptor, lifecycleState, configurationAsserted, Map.of());
    }
}
