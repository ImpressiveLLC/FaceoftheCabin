package com.cabin.orchestrator.devices.model;

import java.util.Set;

/**
 * Static configuration for a device: what it is, what it can do, how to talk to it.
 * Mutable at runtime only through the Device Manager API.
 */
public record DeviceDescriptor(
    String deviceId,
    String name,
    DeviceType type,
    Set<DeviceCapability> capabilities,
    String protocolAdapter,   // "mqtt", "ha_rest", "rtsp", "http_poll", "google_sdm"
    String connectionString,  // MQTT topic prefix, HA entity_id, RTSP URL, HTTP endpoint
    boolean enabled
) {}
