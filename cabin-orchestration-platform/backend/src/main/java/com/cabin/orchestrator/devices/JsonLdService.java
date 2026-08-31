package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D1 (docs/ontology/DECISIONS.md): "snake_case entity_id IS the local URI
 * stem -> cabin:{entity_id} in JSON-LD." This makes that real -- a device's
 * own dereferenceable JSON-LD representation, using the same data KB
 * Generator already draws from (DeviceRegistry, DeviceRepository,
 * DeviceReportingRelationshipRepository). The context itself lives at
 * src/main/resources/context/cabin-context.jsonld (served by
 * JsonLdController) rather than a docs/ copy, so there's exactly one real
 * artifact, not two that could drift apart.
 */
@Service
public class JsonLdService {

    private final DeviceRegistry registry;
    private final DeviceRepository deviceRepository;
    private final DeviceReportingRelationshipRepository reportingRelationshipRepository;

    public JsonLdService(DeviceRegistry registry, DeviceRepository deviceRepository,
                          DeviceReportingRelationshipRepository reportingRelationshipRepository) {
        this.registry = registry;
        this.deviceRepository = deviceRepository;
        this.reportingRelationshipRepository = reportingRelationshipRepository;
    }

    /** Empty when the device isn't known at all -- distinct from a known device with no metadata yet. */
    public Optional<Map<String, Object>> deviceAsJsonLd(String deviceId) {
        DeviceStatus status = registry.get(deviceId);
        if (status == null) return Optional.empty();

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("@context", "/api/context/cabin-context.jsonld");
        node.put("id", "cabin:" + deviceId);
        node.put("type", isActuator(deviceId) ? "Actuator" : "Device");
        node.put("name", status.name());
        node.put("location", status.location());

        deviceRepository.find(deviceId).ifPresent(metadata -> {
            if (metadata.manufacturer() != null) node.put("manufacturer", metadata.manufacturer());
            if (metadata.model() != null) node.put("model", metadata.model());
            if (metadata.area() != null) node.put("area", metadata.area());
        });

        List<String> fields = reportingRelationshipRepository.findByDevice(deviceId).stream()
            .map(DeviceReportingRelationship::semanticField).sorted().toList();
        if (!fields.isEmpty()) node.put("reportsField", fields);

        return Optional.of(node);
    }

    private boolean isActuator(String deviceId) {
        return registry.descriptor(deviceId)
            .map(DeviceDescriptor::capabilities)
            .map(caps -> caps.contains(DeviceCapability.COMMAND))
            .orElse(false);
    }
}
