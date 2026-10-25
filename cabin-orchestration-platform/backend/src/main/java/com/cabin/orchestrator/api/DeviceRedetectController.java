package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * W-93: person-approved re-detect of a configured Zigbee device's type and capabilities. Type
 * and capabilities are sticky once configured (by design), so a device registered under an
 * older inference keeps its old shape. Three steps, all administrator-only (POST, so the
 * signed-in write gate in WebConfig covers them):
 * <ul>
 *   <li>{@code POST /api/devices/redetect/preview} -- dry run, changes nothing: current versus
 *       the adapter's inference from the live Z2M exposes, per device.</li>
 *   <li>{@code POST /api/devices/{id}/redetect/apply} body {@code {"fields":["type","capabilities"]}}
 *       -- applies only the approved fields through DeviceRegistry.replaceConfiguration (the
 *       same path discovery/apply uses); name, enabled state, location and connection are
 *       never touched. Returns a {@code rollback} object and publishes an audit event.</li>
 *   <li>{@code POST /api/devices/{id}/redetect/rollback} -- restores the values from this
 *       process's last apply for the device, or from an explicit {@code {"type","capabilities"}}
 *       body (the {@code rollback} object a past apply returned), which survives a restart.</li>
 * </ul>
 * Nothing here runs on its own: there is no scheduled or automatic caller.
 */
@RestController
@RequestMapping("/api/devices")
@CrossOrigin
public class DeviceRedetectController {

    private static final Set<String> APPLICABLE = Set.of("type", "capabilities");

    private final DeviceRegistry registry;
    private final Zigbee2MqttAdapter zigbee;
    private final EventPublisher events;
    private final Map<String, Snapshot> lastApply = new ConcurrentHashMap<>();

    public DeviceRedetectController(DeviceRegistry registry, Zigbee2MqttAdapter zigbee, EventPublisher events) {
        this.registry = registry;
        this.zigbee = zigbee;
        this.events = events;
    }

    private record Snapshot(DeviceType type, Set<DeviceCapability> capabilities, Set<String> fields) {}

    @PostMapping("/redetect/preview")
    public ResponseEntity<?> preview(HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        List<Map<String, Object>> rows = new ArrayList<>();
        registry.all().stream().filter(s -> s.deviceId().startsWith("z2m-"))
            .sorted(Comparator.comparing(s -> s.deviceId()))
            .forEach(s -> registry.descriptor(s.deviceId()).ifPresent(current ->
                zigbee.inferredShape(s.deviceId()).ifPresent(p -> rows.add(row(current, p)))));
        return ResponseEntity.ok(Map.of("dryRun", true, "devices", rows));
    }

    @PostMapping("/{deviceId}/redetect/apply")
    public ResponseEntity<?> apply(@PathVariable String deviceId, @RequestBody(required = false) Map<String, Object> body,
                                   HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        Optional<DeviceDescriptor> current = registry.descriptor(deviceId);
        Optional<Zigbee2MqttAdapter.InferredShape> proposed = zigbee.inferredShape(deviceId);
        if (current.isEmpty() || proposed.isEmpty())
            return error(HttpStatus.NOT_FOUND, "No Zigbee device or live inference for " + deviceId);
        Set<String> fields = fieldsOf(body);
        if (fields.isEmpty() || !APPLICABLE.containsAll(fields))
            return error(HttpStatus.BAD_REQUEST, "fields must be a non-empty subset of " + APPLICABLE);
        DeviceDescriptor existing = current.get();
        DeviceDescriptor next = new DeviceDescriptor(existing.deviceId(), existing.name(),
            fields.contains("type") ? proposed.get().type() : existing.type(),
            fields.contains("capabilities") ? proposed.get().capabilities() : existing.capabilities(),
            existing.protocolAdapter(), existing.connectionString(), existing.enabled(), existing.location());
        try {
            DeviceRegistry.ConfigurationSaveResult saved = registry.replaceConfiguration(deviceId, next, fields);
            Map<String, Object> rollback = rollbackOf(existing, fields);
            if (saved.changed()) {
                lastApply.put(deviceId, new Snapshot(existing.type(), existing.capabilities(), fields));
                audit(deviceId, "DEVICE_REDETECT_APPLIED", existing, saved.descriptor(), fields);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("deviceId", deviceId);
            out.put("changed", saved.changed());
            out.put("applied", row(saved.descriptor(), proposed.get()));
            out.put("rollback", rollback);
            return ResponseEntity.ok(out);
        } catch (IllegalStateException | IllegalArgumentException | NoSuchElementException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/{deviceId}/redetect/rollback")
    public ResponseEntity<?> rollback(@PathVariable String deviceId, @RequestBody(required = false) Map<String, Object> body,
                                      HttpServletRequest request) {
        ResponseEntity<?> denied = requireAdministrator(request);
        if (denied != null) return denied;
        Optional<DeviceDescriptor> current = registry.descriptor(deviceId);
        if (current.isEmpty()) return error(HttpStatus.NOT_FOUND, "Device not found: " + deviceId);
        Snapshot snap;
        try {
            snap = body != null && body.containsKey("type") ? explicit(body) : lastApply.get(deviceId);
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (snap == null)
            return error(HttpStatus.NOT_FOUND, "No apply to roll back for " + deviceId
                + " in this process; send the rollback object a past apply returned");
        DeviceDescriptor existing = current.get();
        DeviceDescriptor restored = new DeviceDescriptor(existing.deviceId(), existing.name(),
            snap.type(), snap.capabilities(), existing.protocolAdapter(), existing.connectionString(),
            existing.enabled(), existing.location());
        try {
            DeviceRegistry.ConfigurationSaveResult saved = registry.replaceConfiguration(deviceId, restored, snap.fields());
            lastApply.remove(deviceId);
            if (saved.changed()) audit(deviceId, "DEVICE_REDETECT_ROLLED_BACK", existing, saved.descriptor(), snap.fields());
            return ResponseEntity.ok(Map.of("deviceId", deviceId, "changed", saved.changed(),
                "type", saved.descriptor().type().name(),
                "capabilities", names(saved.descriptor().capabilities())));
        } catch (IllegalStateException | IllegalArgumentException | NoSuchElementException e) {
            return error(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    private Snapshot explicit(Map<String, Object> body) {
        Set<DeviceCapability> caps = EnumSet.noneOf(DeviceCapability.class);
        if (body.get("capabilities") instanceof List<?> list) {
            for (Object c : list) caps.add(DeviceCapability.valueOf(String.valueOf(c)));
        }
        DeviceType type = DeviceType.valueOf(String.valueOf(body.get("type")));
        Set<String> fields = body.get("fields") instanceof List<?> f
            ? f.stream().map(String::valueOf).collect(Collectors.toSet()) : APPLICABLE;
        if (!APPLICABLE.containsAll(fields)) throw new IllegalArgumentException("fields must be a subset of " + APPLICABLE);
        return new Snapshot(type, caps, fields);
    }

    private static Set<String> fieldsOf(Map<String, Object> body) {
        if (body == null || !(body.get("fields") instanceof List<?> list)) return Set.of();
        return list.stream().map(String::valueOf).collect(Collectors.toSet());
    }

    private static Map<String, Object> rollbackOf(DeviceDescriptor before, Set<String> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", before.type().name());
        out.put("capabilities", names(before.capabilities()));
        out.put("fields", fields.stream().sorted().toList());
        return out;
    }

    private static Map<String, Object> row(DeviceDescriptor current, Zigbee2MqttAdapter.InferredShape p) {
        List<String> changed = new ArrayList<>();
        if (current.type() != p.type()) changed.add("type");
        if (!current.capabilities().equals(p.capabilities())) changed.add("capabilities");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", current.deviceId());
        out.put("name", current.name());
        out.put("current", Map.of("type", current.type().name(), "capabilities", names(current.capabilities())));
        out.put("proposed", Map.of("type", p.type().name(), "capabilities", names(p.capabilities())));
        out.put("changedFields", changed);
        out.put("changed", !changed.isEmpty());
        return out;
    }

    private static List<String> names(Set<DeviceCapability> caps) {
        return caps.stream().map(Enum::name).sorted().toList();
    }

    private void audit(String deviceId, String eventType, DeviceDescriptor before, DeviceDescriptor after, Set<String> fields) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fields", fields.stream().sorted().toList());
        payload.put("beforeType", before.type().name());
        payload.put("beforeCapabilities", names(before.capabilities()));
        payload.put("afterType", after.type().name());
        payload.put("afterCapabilities", names(after.capabilities()));
        events.publish(new CabinEvent(UUID.randomUUID().toString(), deviceId, eventType, "INFO", Instant.now(), payload));
    }

    private static ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private ResponseEntity<?> requireAdministrator(HttpServletRequest request) {
        HouseholdRole role = (HouseholdRole) request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        if (role != HouseholdRole.ADMINISTRATOR) {
            return error(HttpStatus.FORBIDDEN, "Only an administrator can re-detect device types");
        }
        return null;
    }
}
