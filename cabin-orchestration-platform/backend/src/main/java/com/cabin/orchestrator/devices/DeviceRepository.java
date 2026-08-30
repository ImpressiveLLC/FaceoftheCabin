package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceMetadata;

import java.util.Map;
import java.util.Optional;

/**
 * Persists the D1/D6/D7 real-column device facts (issue #30) and the D4
 * generic provenance mixin (issue #33) -- separate from DeviceLifecycleStore,
 * which owns human-editable config (location, enabled, room) and creates the
 * `device` row in the first place. upsert() only updates an existing row's
 * metadata columns; it is a no-op for a device_id with no row yet (an
 * undecided candidate -- see the known candidate-persistence gap this
 * doesn't attempt to close).
 */
public interface DeviceRepository {
    void upsert(String deviceId, DeviceMetadata metadata);
    Optional<DeviceMetadata> find(String deviceId);
    Map<String, DeviceMetadata> loadAll();
}
