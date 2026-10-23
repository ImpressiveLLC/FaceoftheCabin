package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceStatus;
import java.util.*;

/** Read-only physical grouping; observation IDs, enrollment and command routing never change. */
public final class SmartSwitchMetadata {
    private SmartSwitchMetadata() {}

    static String identity(DeviceStatus status) {
        if (!status.deviceId().startsWith("z2m-")) return "";
        Object value = status.attributes().get("ieeeAddress");
        String ieee = value == null ? status.deviceId().substring(status.deviceId().lastIndexOf('-') + 1)
            : String.valueOf(value).toLowerCase(Locale.ROOT);
        return ieee.matches("0x[0-9a-f]{16}") ? "zigbee:" + status.location() + ":" + ieee : "";
    }

    static void enrich(DeviceStatus status, Map<String, Object> attrs, Collection<DeviceStatus> all) {
        String identity = identity(status);
        if (identity.isEmpty()) return;
        attrs.put("physicalIdentity", identity);
        List<DeviceStatus> aliases = all.stream().filter(s -> identity.equals(identity(s))).toList();
        if (aliases.stream().anyMatch(s -> Boolean.TRUE.equals(s.attributes().get("smartSwitch"))))
            attrs.put("smartSwitch", true);
        List<DeviceStatus> assigned = aliases.stream()
            .filter(s -> "ASSIGNED".equals(s.attributes().get("deviceLifecycle"))).toList();
        if (assigned.size() == 1) attrs.put("canonicalPhysicalId", assigned.get(0).deviceId());
        else if (aliases.size() == 1) attrs.put("canonicalPhysicalId", status.deviceId());
        else attrs.put("physicalIdentityStatus", "ambiguous");
    }
}
