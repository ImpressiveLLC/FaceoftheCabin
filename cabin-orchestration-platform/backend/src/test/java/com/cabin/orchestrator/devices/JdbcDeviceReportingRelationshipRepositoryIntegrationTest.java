package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcDeviceReportingRelationshipRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcDeviceReportingRelationshipRepository newRepository() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcDeviceReportingRelationshipRepository(jdbc);
    }

    @Test
    void upsertPersistsAcrossInstances() {
        JdbcDeviceReportingRelationshipRepository first = newRepository();
        first.upsert(new DeviceReportingRelationship(
            "z2m-temp_kitchen_a", "temperature", "temperature", ConfirmationSource.EMPIRICAL_OBSERVATION, Instant.now()));

        JdbcDeviceReportingRelationshipRepository restarted = newRepository();
        List<DeviceReportingRelationship> found = restarted.findByDevice("z2m-temp_kitchen_a");

        assertEquals(1, found.size());
        assertEquals("temperature", found.get(0).semanticField());
        assertEquals(ConfirmationSource.EMPIRICAL_OBSERVATION, found.get(0).confirmationSource());
    }

    @Test
    void vendorSpecIsNeverDowngradedByALaterEmpiricalObservation() {
        JdbcDeviceReportingRelationshipRepository repository = newRepository();
        repository.upsert(new DeviceReportingRelationship(
            "z2m-temp_kitchen_b", "humidity", "humidity", ConfirmationSource.VENDOR_SPEC, Instant.now()));

        repository.upsert(new DeviceReportingRelationship(
            "z2m-temp_kitchen_b", "humidity", "humidity", ConfirmationSource.EMPIRICAL_OBSERVATION, Instant.now()));

        DeviceReportingRelationship found = repository.findByDevice("z2m-temp_kitchen_b").get(0);
        assertEquals(ConfirmationSource.VENDOR_SPEC, found.confirmationSource(),
            "a stronger claim already on record must survive a weaker later confirmation");
    }

    @Test
    void manualOverrideAlwaysWinsEvenOverVendorSpec() {
        JdbcDeviceReportingRelationshipRepository repository = newRepository();
        repository.upsert(new DeviceReportingRelationship(
            "z2m-temp_kitchen_c", "co2", "co2", ConfirmationSource.VENDOR_SPEC, Instant.now()));

        repository.upsert(new DeviceReportingRelationship(
            "z2m-temp_kitchen_c", "co2", "co2", ConfirmationSource.MANUAL_OVERRIDE, Instant.now()));

        DeviceReportingRelationship found = repository.findByDevice("z2m-temp_kitchen_c").get(0);
        assertEquals(ConfirmationSource.MANUAL_OVERRIDE, found.confirmationSource());
    }

    @Test
    void reconfirmingTheSameSourceRefreshesConfirmedAt() {
        JdbcDeviceReportingRelationshipRepository repository = newRepository();
        Instant firstConfirmed = Instant.now().minusSeconds(600);
        repository.upsert(new DeviceReportingRelationship(
            "z2m-temp_kitchen_d", "temperature", "temperature", ConfirmationSource.EMPIRICAL_OBSERVATION, firstConfirmed));

        Instant secondConfirmed = Instant.now();
        repository.upsert(new DeviceReportingRelationship(
            "z2m-temp_kitchen_d", "temperature", "temperature", ConfirmationSource.EMPIRICAL_OBSERVATION, secondConfirmed));

        DeviceReportingRelationship found = repository.findByDevice("z2m-temp_kitchen_d").get(0);
        assertEquals(secondConfirmed.getEpochSecond(), found.confirmedAt().getEpochSecond(),
            "same-priority reconfirmation must still refresh confirmed_at, not be treated as a downgrade");
    }

    @Test
    void loadAllGroupsByDeviceId() {
        JdbcDeviceReportingRelationshipRepository repository = newRepository();
        repository.upsert(new DeviceReportingRelationship(
            "z2m-multi", "temperature", "temperature", ConfirmationSource.VENDOR_SPEC, Instant.now()));
        repository.upsert(new DeviceReportingRelationship(
            "z2m-multi", "humidity", "humidity", ConfirmationSource.VENDOR_SPEC, Instant.now()));

        var all = repository.loadAll();

        assertEquals(2, all.get("z2m-multi").size());
    }
}
