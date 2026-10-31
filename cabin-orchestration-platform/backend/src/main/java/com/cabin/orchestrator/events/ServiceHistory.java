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
 * Found again 2026-10-26: the Mech Room and Kidde humidity columns went blank because their parents are
 * {@code ha-*} entities (a Home Assistant device whose sensor entities link to one entity as parent), and the
 * fold only started from a {@code z2m-*} parent. The readings were never lost: they sit under the linked
 * children. Any parent with linked sensor entities now folds them, not only a native Zigbee one.
 *
 * Only {@code ha-*} {@code sensor.*} children at the same location as their parent are folded in; nothing
 * else changes meaning.
 */
public final class ServiceHistory {

    /** Attribute name on the current device that lists the ids its readings were logged under before. */
    public static final String FORMER_DEVICE_IDS = "formerDeviceIds";

    private ServiceHistory() {}

    /** The device itself plus every Home Assistant sensor entity linked under it, parent first. */
    public static List<String> idsFor(String deviceId, DeviceRegistry registry) {
        List<String> ids = new ArrayList<>();
        ids.add(deviceId);
        DeviceStatus parent = registry.get(deviceId);
        if (parent == null) return ids;
        for (DeviceStatus child : registry.all()) {
            if (isSensorServiceOf(child, deviceId, parent.location())) ids.add(child.deviceId());
        }
        for (String former : formerIds(parent, registry)) {
            if (!ids.contains(former)) ids.add(former);
        }
        return ids;
    }

    /**
     * W-101b ruling 5 (D3): ids this device was logged under before, kept on the current parent as the
     * {@code formerDeviceIds} attribute. History only: it is never a parent id, never validated as one and
     * never routes a command or a reading. A former id that is also a live row's id is ignored here, so a
     * second device's own history can never be folded in by a stale entry.
     */
    public static List<String> formerIds(DeviceStatus device, DeviceRegistry registry) {
        Object raw = device.attributes().get(FORMER_DEVICE_IDS);
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof String s) || s.isBlank()) continue;
            String id = s.trim();
            if (id.equals(device.deviceId()) || registry.get(id) != null || out.contains(id)) continue;
            out.add(id);
        }
        return out;
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
        // Former ids (D3). The owner need not have logged anything itself, and a disabled owner leaves the
        // former entry in the picker, as for the entity fold above.
        for (DeviceStatus owner : registry.all()) {
            Optional<DeviceDescriptor> descriptor = registry.descriptor(owner.deviceId());
            if (descriptor.isEmpty() || !descriptor.get().enabled()) continue;
            for (String former : formerIds(owner, registry)) {
                Set<String> fields = merged.remove(former);
                if (fields != null) merged.computeIfAbsent(owner.deviceId(), k -> new LinkedHashSet<>()).addAll(fields);
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        merged.forEach((id, fields) -> result.put(id, new ArrayList<>(fields)));
        return result;
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
