package com.cabin.orchestrator.devices.display;

import java.util.Map;

/**
 * Per-device display overrides that apply when a given PresenceProfile is active.
 *
 * displayName:    null = use device's registered name
 * stateLabelMap:  empty = show raw state strings; entries override specific state keys
 *                 e.g. {"ONLINE":"Unlocked","OFFLINE":"Offline"}
 * severityOverride: null = derive from device status; "OK"/"WARN"/"ALERT" = forced
 */
public record DeviceDisplayConfig(
    String deviceId,
    String location,
    String presenceProfile,
    String displayName,
    Map<String, String> stateLabelMap,
    String severityOverride
) {}
