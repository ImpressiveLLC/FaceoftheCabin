package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;

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
 */
public record DeviceLifecycleRecord(
    DeviceDescriptor descriptor,
    DeviceLifecycleState lifecycleState,
    boolean configurationAsserted,
    Map<String, Object> extraAttributes
) {
    public DeviceLifecycleRecord(DeviceDescriptor descriptor, DeviceLifecycleState lifecycleState,
                                  boolean configurationAsserted) {
        this(descriptor, lifecycleState, configurationAsserted, Map.of());
    }
}
