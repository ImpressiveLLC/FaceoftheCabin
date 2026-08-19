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

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcDeviceLifecycleStoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * 2026-08-18: extraAttributes (room, first use) must round-trip through
     * a real restart just like lifecycleState/configurationAsserted above --
     * this is the "room persists permanently, not just in memory until the
     * next backend restart" guarantee the UX rework's device-level room
     * field depends on.
     */
    @Test
    void extraAttributesSurviveANewStoreInstance() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcDeviceLifecycleStore first = new JdbcDeviceLifecycleStore(jdbc, mapper);
        DeviceDescriptor descriptor = new DeviceDescriptor(
            "fridge-partymode", "Party Mode", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.COMMAND),
            "ha_rest", "switch.loonie_mc_frigerton_partymode", true, "cabin");

        first.save(new DeviceLifecycleRecord(descriptor, DeviceLifecycleState.ASSIGNED, true,
            Map.of("room", "Kitchen")));
        JdbcDeviceLifecycleStore restarted = new JdbcDeviceLifecycleStore(jdbc, mapper);
        DeviceLifecycleRecord restored = restarted.loadAll().get("fridge-partymode");

        assertNotNull(restored);
        assertEquals(Map.of("room", "Kitchen"), restored.extraAttributes());
    }

    /**
     * The real risk this whole design is built around: JdbcDeviceLifecycleStore
     * still writes name/enabled/etc. on EVERY save() (see toJson()), but only
     * includes room when the caller's extraAttributes actually has it. An
     * unrelated later save (e.g. just flipping enabled, extraAttributes empty)
     * must not overwrite the DB's config JSONB in a way that drops the room
     * a previous save set -- Postgres's `||` merge is what makes this true,
     * this test is what proves it stays true.
     */
    @Test
    void aLaterSaveWithNoExtraAttributesDoesNotWipeAPreviouslySetRoom() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcDeviceLifecycleStore store = new JdbcDeviceLifecycleStore(jdbc, mapper);
        DeviceDescriptor descriptor = new DeviceDescriptor(
            "kidde-co", "Kidde CO Alarm", DeviceType.CO_ALARM,
            Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ALARM),
            "ha_rest", "binary_sensor.kidde_air_quality_alarm_carbon_monoxide_alarm", true, "cabin");

        store.save(new DeviceLifecycleRecord(descriptor, DeviceLifecycleState.ASSIGNED, true,
            Map.of("room", "Mechanical Room")));
        // A later, unrelated save -- e.g. just a name edit -- carries no
        // extraAttributes at all, matching saveConfiguration(id, name,
        // enabled)'s 3-arg overload's real Map.of() default.
        DeviceDescriptor renamed = new DeviceDescriptor(
            descriptor.deviceId(), "Kidde CO/Smoke Alarm", descriptor.type(), descriptor.capabilities(),
            descriptor.protocolAdapter(), descriptor.connectionString(), descriptor.enabled(), descriptor.location());
        store.save(new DeviceLifecycleRecord(renamed, DeviceLifecycleState.ASSIGNED, true));

        DeviceLifecycleRecord reloaded = store.loadAll().get("kidde-co");
        assertEquals("Kidde CO/Smoke Alarm", reloaded.descriptor().name());
        assertEquals(Map.of("room", "Mechanical Room"), reloaded.extraAttributes(),
            "a save() with no extraAttributes must not erase a room set by an earlier save()");
    }

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
