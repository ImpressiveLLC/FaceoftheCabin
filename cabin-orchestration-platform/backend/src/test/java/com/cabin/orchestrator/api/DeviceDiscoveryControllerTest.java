package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceLifecycleRecord;
import com.cabin.orchestrator.devices.DeviceLifecycleStore;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.JdbcDeviceDiscoveryStore;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.integrations.discovery.DiscoveryServiceClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeviceDiscoveryController's own logic (rate limiting, the
 * discoverySuggested dismiss hook) is covered here directly against the
 * controller, not through DeviceRegistryTest or
 * JdbcDeviceDiscoveryStoreIntegrationTest, since neither of those exercise
 * the request-shaping code that lives in the controller itself. Same
 * no-Spring-context Testcontainers pattern as
 * JdbcDeviceDiscoveryStoreIntegrationTest -- DiscoveryServiceClient is
 * real, not mocked: with no cabin-discovery host reachable in this
 * environment, it deterministically falls through to its own
 * local-only-fallback path, which is enough to exercise the controller
 * without a live Python service.
 */
@Testcontainers
class DeviceDiscoveryControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcDeviceDiscoveryStore newStore() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcDeviceDiscoveryStore(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    private DeviceRegistry newRegistry() {
        DeviceLifecycleStore inMemory = new DeviceLifecycleStore() {
            private final Map<String, DeviceLifecycleRecord> records = new LinkedHashMap<>();
            @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.copyOf(records); }
            @Override public void save(DeviceLifecycleRecord record) { records.put(record.descriptor().deviceId(), record); }
            @Override public void delete(String deviceId) { records.remove(deviceId); }
        };
        return new DeviceRegistry(List.of(), inMemory);
    }

    private DeviceDiscoveryController newController(DeviceRegistry registry) {
        return new DeviceDiscoveryController(registry, newStore(), new DiscoveryServiceClient());
    }

    @Test
    void secondRunWithinCooldownIsRateLimitedAndDoesNotStartANewRun() {
        DeviceRegistry registry = newRegistry();
        registry.registerCandidate(new DeviceDescriptor(
            "rl-1", "Mystery", DeviceType.TEMPERATURE_SENSOR, Set.of(DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/rl-1", false, "cabin"), Map.of());
        DeviceDiscoveryController controller = newController(registry);

        Map<String, Object> first = controller.runDiscovery("rl-1");
        assertNotNull(first.get("runId"));
        assertNull(first.get("error"));

        Map<String, Object> second = controller.runDiscovery("rl-1");
        assertNotNull(second.get("error"), "an immediate repeat run must be rejected, not silently re-queued");
        assertNull(second.get("runId"));
        assertNotNull(second.get("retryAfterSeconds"));
    }

    @Test
    void rateLimitIsPerDeviceNotGlobal() {
        DeviceRegistry registry = newRegistry();
        registry.registerCandidate(new DeviceDescriptor(
            "rl-a", "A", DeviceType.TEMPERATURE_SENSOR, Set.of(DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/rl-a", false, "cabin"), Map.of());
        registry.registerCandidate(new DeviceDescriptor(
            "rl-b", "B", DeviceType.TEMPERATURE_SENSOR, Set.of(DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/rl-b", false, "cabin"), Map.of());
        DeviceDiscoveryController controller = newController(registry);

        Map<String, Object> a = controller.runDiscovery("rl-a");
        Map<String, Object> b = controller.runDiscovery("rl-b");

        assertNotNull(a.get("runId"));
        assertNotNull(b.get("runId"));
        assertNull(a.get("error"));
        assertNull(b.get("error"));
    }

    @Test
    void runDiscoveryClearsTheDiscoverySuggestedNudge() {
        DeviceRegistry registry = newRegistry();
        registry.registerCandidate(new DeviceDescriptor(
            "rl-2", "Mystery 2", DeviceType.MOTION_SENSOR, Set.of(DeviceCapability.PRESENCE),
            "mqtt", "zigbee2mqtt/rl-2", false, "cabin"), Map.of());
        assertEquals(true, registry.get("rl-2").attributes().get("discoverySuggested"));
        DeviceDiscoveryController controller = newController(registry);

        controller.runDiscovery("rl-2");

        assertNull(registry.get("rl-2").attributes().get("discoverySuggested"));
    }

    @Test
    void runDiscoveryOnUnknownDeviceReturnsErrorWithoutThrowing() {
        DeviceDiscoveryController controller = newController(newRegistry());

        Map<String, Object> result = controller.runDiscovery("does-not-exist");

        assertEquals("not found", result.get("error"));
    }
}
