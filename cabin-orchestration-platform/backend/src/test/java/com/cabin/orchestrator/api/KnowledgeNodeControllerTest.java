package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceRepository;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.JdbcKnowledgeNodeRepository;
import com.cabin.orchestrator.devices.KbGeneratorService;
import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring only -- KbGeneratorService's own content logic is covered in KbGeneratorServiceTest. */
@Testcontainers
class KnowledgeNodeControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private KnowledgeNodeController newController(DeviceRegistry registry) {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        KnowledgeNodeRepository knowledgeNodeRepository = new JdbcKnowledgeNodeRepository(jdbc);
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
        KbGeneratorService generator = new KbGeneratorService(registry, noOpDeviceRepository, noOpReportingRepository, knowledgeNodeRepository);
        return new KnowledgeNodeController(generator, knowledgeNodeRepository);
    }

    @Test
    void regenerateWritesKnowledgeNodesForEveryInScopeDevice() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-controller_test", "Controller test device", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic", true, "cabin"), Map.of());
        registry.applyLifecycleAction("z2m-controller_test", DeviceLifecycleAction.ACCEPT);
        KnowledgeNodeController controller = newController(registry);

        Map<String, Integer> result = controller.regenerate();

        assertTrue(result.get("chunksWritten") >= 1);
        assertFalse(controller.nodesFor("z2m-controller_test").isEmpty());
    }

    @Test
    void regenerateOneOnlyTouchesTheNamedDevice() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-single_target", "Single target device", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic", true, "cabin"), Map.of());
        registry.applyLifecycleAction("z2m-single_target", DeviceLifecycleAction.ACCEPT);
        KnowledgeNodeController controller = newController(registry);

        controller.regenerateOne("z2m-single_target");

        assertFalse(controller.nodesFor("z2m-single_target").isEmpty());
    }
}
