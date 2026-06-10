package com.cabin.orchestrator.devices.model;

import java.time.Instant;
import java.util.Map;

/**
 * Runtime state of a registered device — updated on every telemetry/state message.
 */
public record DeviceStatus(
    String deviceId,
    DeviceType type,
    String name,
    String state,           // ONLINE, OFFLINE, ALARM, UNKNOWN
    Instant lastSeen,
    Map<String, Object> attributes  // metric bag: psi, temp_f, locked, motion, etc.
) {}
