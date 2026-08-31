package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceRepository;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.JsonLdService;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JsonLdControllerTest {

    private JsonLdController newController(DeviceRegistry registry) {
        DeviceRepository noOpDeviceRepository = new DeviceRepository() {
            @Override public void upsert(String deviceId, DeviceMetadata metadata) { }
            @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.empty(); }
            @Override public Map<String, DeviceMetadata> loadAll() { return Map.of(); }
        };
        DeviceReportingRelationshipRepository noOpReportingRepository = new DeviceReportingRelationshipRepository() {
            @Override public void upsert(DeviceReportingRelationship relationship) { }
            @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) { return List.of(); }
            @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.of(); }
        };
        return new JsonLdController(new JsonLdService(registry, noOpDeviceRepository, noOpReportingRepository));
    }

    @Test
    void contextEndpointServesTheRealContextResource() throws Exception {
        JsonLdController controller = newController(new DeviceRegistry(List.of()));

        ResponseEntity<org.springframework.core.io.Resource> response = controller.context();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        String body = new String(response.getBody().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(body.contains("\"@context\""));
        assertTrue(body.contains("sosa:Sensor"), "must define the SSN/SOSA mapping D1 specifies");
    }

    @Test
    void knownDeviceReturns200WithItsJsonLdBody() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-jsonld_test", "JSON-LD test device", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic", true, "cabin"), Map.of());
        registry.applyLifecycleAction("z2m-jsonld_test", DeviceLifecycleAction.ACCEPT);
        JsonLdController controller = newController(registry);

        ResponseEntity<Map<String, Object>> response = controller.device("z2m-jsonld_test");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("cabin:z2m-jsonld_test", response.getBody().get("id"));
    }

    @Test
    void unknownDeviceReturns404() {
        JsonLdController controller = newController(new DeviceRegistry(List.of()));

        ResponseEntity<Map<String, Object>> response = controller.device("z2m-does_not_exist");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }
}
