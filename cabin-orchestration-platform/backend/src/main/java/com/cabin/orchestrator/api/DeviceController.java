package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/devices")
@CrossOrigin
public class DeviceController {

    private final DeviceRegistry registry;

    public DeviceController(DeviceRegistry registry) {
        this.registry = registry;
    }

    /** List all registered devices with their current state */
    @GetMapping
    public List<DeviceStatus> listDevices() {
        return registry.all();
    }

    /** Get single device */
    @GetMapping("/{deviceId}")
    public DeviceStatus getDevice(@PathVariable String deviceId) {
        return registry.get(deviceId);
    }

    /** Register a new device (Device Manager UI → add device) */
    @PostMapping
    public DeviceDescriptor registerDevice(@RequestBody DeviceDescriptor descriptor) {
        registry.register(new DeviceStatus(
            descriptor.deviceId(), descriptor.type(), descriptor.name(),
            "UNKNOWN", Instant.now(), Map.of(), descriptor.location()));
        registry.registerDescriptor(descriptor);
        return descriptor;
    }

    /** Update device config */
    @PutMapping("/{deviceId}")
    public DeviceDescriptor updateDevice(@PathVariable String deviceId,
                                          @RequestBody DeviceDescriptor descriptor) {
        registry.registerDescriptor(descriptor);
        return descriptor;
    }

    /** Remove device */
    @DeleteMapping("/{deviceId}")
    public Map<String, String> removeDevice(@PathVariable String deviceId) {
        registry.remove(deviceId);
        return Map.of("removed", deviceId);
    }

    /** Send a command to a device */
    @PostMapping("/{deviceId}/command")
    public Map<String, Object> sendCommand(@PathVariable String deviceId,
                                            @RequestBody Map<String, Object> body) {
        String command = String.valueOf(body.get("command"));
        Object payload = body.get("payload");
        boolean ok = registry.sendCommand(deviceId, command, payload);
        return Map.of("deviceId", deviceId, "command", command, "accepted", ok);
    }

    /** List available device types and capabilities (Device Manager discovery) */
    @GetMapping("/meta/types")
    public Map<String, Object> deviceMeta() {
        return Map.of(
            "types", DeviceType.values(),
            "capabilities", DeviceCapability.values(),
            "adapters", List.of("mqtt", "ha_rest", "rtsp", "http_poll", "google_sdm")
        );
    }
}
