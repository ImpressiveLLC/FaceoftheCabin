package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcDeviceRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate newJdbc() {
        return new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    /** A `device` row must already exist -- JdbcDeviceLifecycleStore owns row creation, matching production order. */
    private void seedDeviceRow(JdbcTemplate jdbc, String deviceId) {
        JdbcDeviceLifecycleStore lifecycleStore = new JdbcDeviceLifecycleStore(jdbc, new ObjectMapper().findAndRegisterModules());
        lifecycleStore.save(new DeviceLifecycleRecord(
            new DeviceDescriptor(deviceId, "Kitchen temp", DeviceType.TEMPERATURE_SENSOR,
                Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/temp_kitchen", true, "cabin"),
            DeviceLifecycleState.ASSIGNED, true));
    }

    @Test
    void upsertOnAnExistingRowPersistsMetadataAcrossInstances() {
        JdbcTemplate jdbc = newJdbc();
        seedDeviceRow(jdbc, "z2m-temp_kitchen_1");
        JdbcDeviceRepository first = new JdbcDeviceRepository(jdbc);
        Instant pairedAt = Instant.now().minusSeconds(3600);

        first.upsert("z2m-temp_kitchen_1", new DeviceMetadata(
            "SONOFF", "SNZB-02WD", null, pairedAt, null, null, "system", null, 0));

        JdbcDeviceRepository restarted = new JdbcDeviceRepository(jdbc);
        DeviceMetadata found = restarted.find("z2m-temp_kitchen_1").orElseThrow();
        assertEquals("SONOFF", found.manufacturer());
        assertEquals("SNZB-02WD", found.model());
        assertEquals(pairedAt.getEpochSecond(), found.pairedAt().getEpochSecond());
        assertEquals("system", found.modifiedBy());
        assertEquals(2, found.version(), "row starts at version 1 (JdbcDeviceLifecycleStore.save); one upsert increments it");
    }

    @Test
    void upsertIsANoOpWhenNoDeviceRowExistsYet() {
        JdbcTemplate jdbc = newJdbc();
        JdbcDeviceRepository repository = new JdbcDeviceRepository(jdbc);

        repository.upsert("z2m-never-confirmed", new DeviceMetadata(
            "SONOFF", "SNZB-04P", null, Instant.now(), null, null, "system", null, 0));

        assertTrue(repository.find("z2m-never-confirmed").isEmpty(),
            "an undecided candidate has no device row yet -- upsert must not fabricate one");
    }

    @Test
    void upsertNeverOverwritesAnExistingFieldWithNull() {
        JdbcTemplate jdbc = newJdbc();
        seedDeviceRow(jdbc, "z2m-temp_kitchen_2");
        JdbcDeviceRepository repository = new JdbcDeviceRepository(jdbc);
        repository.upsert("z2m-temp_kitchen_2", new DeviceMetadata(
            "SONOFF", "SNZB-02WD", "mech_room", Instant.now(), null, null, "system", null, 0));

        // A later rediscovery that only confirms vendor (e.g. a different
        // adapter/path that never populates model) must not blank out what
        // an earlier, more informative discovery already recorded.
        repository.upsert("z2m-temp_kitchen_2", new DeviceMetadata(
            "SONOFF", null, null, null, null, null, "system", null, 0));

        DeviceMetadata found = repository.find("z2m-temp_kitchen_2").orElseThrow();
        assertEquals("SNZB-02WD", found.model(), "model must survive an upsert that doesn't carry one");
        assertEquals("mech_room", found.area(), "area must survive an upsert that doesn't carry one");
    }

    @Test
    void pairedAtIsSetOnceAndNeverOverwrittenByALaterUpsert() {
        JdbcTemplate jdbc = newJdbc();
        seedDeviceRow(jdbc, "z2m-temp_kitchen_3");
        JdbcDeviceRepository repository = new JdbcDeviceRepository(jdbc);
        Instant firstPaired = Instant.now().minusSeconds(7200);
        repository.upsert("z2m-temp_kitchen_3", new DeviceMetadata(
            "SONOFF", "SNZB-02WD", null, firstPaired, null, null, "system", null, 0));

        repository.upsert("z2m-temp_kitchen_3", new DeviceMetadata(
            "SONOFF", "SNZB-02WD", null, Instant.now(), null, null, "system", null, 0));

        DeviceMetadata found = repository.find("z2m-temp_kitchen_3").orElseThrow();
        assertEquals(firstPaired.getEpochSecond(), found.pairedAt().getEpochSecond(),
            "paired_at is a one-time historical fact, not a last-seen timestamp");
    }
}
