package com.cabin.orchestrator.devices;

import java.util.Map;

/** Persistence boundary used before applying human-authored lifecycle changes in memory. */
public interface DeviceLifecycleStore {
    Map<String, DeviceLifecycleRecord> loadAll();
    void save(DeviceLifecycleRecord record);
    void delete(String deviceId);
}
