package com.cabin.orchestrator.events;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * D13 Amendment A1 (W-88): a native Zigbee device is the parent of its Home Assistant sensor entities, and
 * only the parent logs a reading from now on. The readings logged before that (and any logged under a
 * linked entity since) stay where they are, so a chart or history query for the parent has to read them
 * too, or the series appears to stop the day the link was made (found 2026-10-24 on the cabin's outside
 * temperature sensor: "Temp Outside | Lower Level Temperature" lost every reading after 2026-10-20).
 *
 * Only {@code z2m-*} parents and {@code ha-*} {@code sensor.*} children at the same location are folded
 * together; nothing else changes meaning.
 */
public final class ServiceHistory {

    private ServiceHistory() {}

    /** The device itself plus every Home Assistant sensor entity linked under it, parent first. */
    public static List<String> idsFor(String deviceId, DeviceRegistry registry) {
        List<String> ids = new ArrayList<>();
        ids.add(deviceId);
        DeviceStatus parent = registry.get(deviceId);
        if (parent == null || !isNativeZigbee(deviceId)) return ids;
        for (DeviceStatus child : registry.all()) {
            if (isSensorServiceOf(child, deviceId, parent.location())) ids.add(child.deviceId());
        }
        return ids;
    }

    /**
     * {@code reported-fields} with each folded entity's fields moved onto its enabled Zigbee parent and the
     * entity's own entry removed, so a picker offers one series per physical device. A parent that is
     * disabled leaves its entities as they were (otherwise the series would vanish from the picker).
     */
    public static Map<String, List<String>> mergeReportedFields(Map<String, List<String>> raw, DeviceRegistry registry) {
        Map<String, Set<String>> merged = new LinkedHashMap<>();
        raw.forEach((id, fields) -> merged.put(id, new LinkedHashSet<>(fields)));
        for (String id : new ArrayList<>(raw.keySet())) {
            DeviceStatus child = registry.get(id);
            if (child == null) continue;
            String parentId = parentOf(child);
            DeviceStatus parent = parentId.isEmpty() ? null : registry.get(parentId);
            if (parent == null || !isSensorServiceOf(child, parentId, parent.location())) continue;
            Optional<DeviceDescriptor> parentDescriptor = registry.descriptor(parentId);
            if (parentDescriptor.isEmpty() || !parentDescriptor.get().enabled()) continue;
            merged.computeIfAbsent(parentId, k -> new LinkedHashSet<>()).addAll(merged.remove(id));
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        merged.forEach((id, fields) -> result.put(id, new ArrayList<>(fields)));
        return result;
    }

    private static boolean isNativeZigbee(String deviceId) {
        return deviceId.startsWith("z2m-");
    }

    private static boolean isSensorServiceOf(DeviceStatus child, String parentId, String parentLocation) {
        if (!child.deviceId().startsWith("ha-")) return false;
        if (!Objects.equals(child.location(), parentLocation)) return false;
        if (!parentId.equals(parentOf(child))) return false;
        Object entityId = child.attributes().get("entityId");
        return entityId instanceof String s && s.startsWith("sensor.");
    }

    private static String parentOf(DeviceStatus status) {
        Object parent = status.attributes().get("parentDeviceId");
        return parent == null ? "" : String.valueOf(parent).trim();
    }
}
