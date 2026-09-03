package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceHealthMonitor;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.JdbcDeviceLifecycleVocabularyStore;
import com.cabin.orchestrator.devices.display.DeviceDisplayConfigService;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused on removeDevice() only -- confirmed 2026-09-02 this endpoint had
 * been doing a real DELETE FROM device against the user's actual original
 * intent (mute + retain, not destroy). No broader DeviceController test
 * file exists yet; scoped narrowly here rather than attempting full
 * coverage of every endpoint as part of this specific fix.
 */
@Testcontainers
class DeviceControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private DeviceController newController(DeviceRegistry registry) {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        // z2mAdapter is only dereferenced from checkHealth()'s tryActiveRecovery(),
        // never triggered by refreshAfterConfigurationChange() (the only
        // DeviceHealthMonitor method removeDevice() calls) -- null is safe.
        DeviceHealthMonitor healthMonitor = new DeviceHealthMonitor(registry, null);
        return new DeviceController(registry, null, healthMonitor,
            new DeviceDisplayConfigService(null), new JdbcDeviceLifecycleVocabularyStore(jdbc));
    }

    private DeviceRegistry registryWithAssignedDevice(String deviceId) {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerCandidate(new DeviceDescriptor(
            deviceId, "Test Device", DeviceType.MOTION_SENSOR, Set.of(DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/" + deviceId, true, "cabin"), Map.of());
        registry.applyLifecycleAction(deviceId, DeviceLifecycleAction.ACCEPT);
        registry.saveConfiguration(deviceId, "Test Device", true);
        return registry;
    }

    @Test
    void removeDeviceIgnoresRatherThanDeletes() {
        DeviceRegistry registry = registryWithAssignedDevice("z2m-old-sensor");
        DeviceController controller = newController(registry);

        Map<String, Object> result = controller.removeDevice("z2m-old-sensor");

        assertEquals("z2m-old-sensor", result.get("deviceId"));
        assertEquals(com.cabin.orchestrator.devices.model.DeviceLifecycleState.IGNORED, result.get("deviceLifecycle"));
        assertTrue(registry.descriptor("z2m-old-sensor").isPresent(),
            "the device's data must still exist -- IGNORE retains, unlike the real delete this replaced");
    }

    @Test
    void aRemovedDeviceIsGoneFromTheVisibleListButStillInPreviouslyExposed() {
        DeviceRegistry registry = registryWithAssignedDevice("z2m-old-sensor");
        DeviceController controller = newController(registry);

        controller.removeDevice("z2m-old-sensor");

        assertTrue(registry.visible().stream().noneMatch(d -> d.deviceId().equals("z2m-old-sensor")));
        assertTrue(registry.previouslyExposed().stream().anyMatch(d -> d.deviceId().equals("z2m-old-sensor")),
            "a removed device must be reversible via the previously-exposed review screen, not just gone");
    }

    @Test
    void removingAnAlreadyRemovedDeviceIsANoOp() {
        DeviceRegistry registry = registryWithAssignedDevice("z2m-old-sensor");
        DeviceController controller = newController(registry);
        controller.removeDevice("z2m-old-sensor");

        Map<String, Object> result = controller.removeDevice("z2m-old-sensor");

        assertEquals(false, result.get("changed"));
        assertEquals(com.cabin.orchestrator.devices.model.DeviceLifecycleState.IGNORED, result.get("deviceLifecycle"));
    }
}
