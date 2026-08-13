package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcDeviceLifecycleStoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void lifecycleAndDescriptorSurviveANewStoreInstance() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcDeviceLifecycleStore first = new JdbcDeviceLifecycleStore(jdbc, mapper);
        DeviceDescriptor descriptor = new DeviceDescriptor(
            "persisted-device", "Entry sensor", DeviceType.CONTACT_SENSOR,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ACCESS_CONTROL),
            "mqtt", "zigbee2mqtt/entry", false, "cabin");

        first.save(new DeviceLifecycleRecord(descriptor, DeviceLifecycleState.DEFERRED, true));
        JdbcDeviceLifecycleStore restarted = new JdbcDeviceLifecycleStore(jdbc, mapper);
        DeviceLifecycleRecord restored = restarted.loadAll().get("persisted-device");

        assertNotNull(restored);
        assertEquals(DeviceLifecycleState.DEFERRED, restored.lifecycleState());
        assertEquals(descriptor, restored.descriptor());
        assertTrue(restored.configurationAsserted());

        restarted.delete("persisted-device");
        assertFalse(first.loadAll().containsKey("persisted-device"));
    }
}
