package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDiscoveryResult;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcDeviceDiscoveryStoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcDeviceDiscoveryStore newStore() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcDeviceDiscoveryStore(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    @Test
    void resultSurvivesANewStoreInstanceAndReportsAsLatest() {
        JdbcDeviceDiscoveryStore first = newStore();
        DeviceDiscoveryResult.Source source = new DeviceDiscoveryResult.Source(
            "https://example.com/spec-sheet", "Example Vendor spec sheet",
            "SNZB-04P is a wireless contact sensor...", Instant.now());
        DeviceDiscoveryResult.Match match = new DeviceDiscoveryResult.Match(
            "SONOFF SNZB-04P — wireless door/window contact sensor", "high", "Front door contact",
            DeviceType.CONTACT_SENSOR, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.ACCESS_CONTROL),
            List.of(), null,
            new DeviceDiscoveryResult.InstallGuide("summary", "Pair within 30s of powering on."),
            List.of(source));
        DeviceDiscoveryResult result = new DeviceDiscoveryResult(
            "run-1", "z2m-door_second_contact", Instant.now(), null, List.of(match));

        first.save(result);
        JdbcDeviceDiscoveryStore restarted = newStore();
        Optional<DeviceDiscoveryResult> restored = restarted.latestFor("z2m-door_second_contact");

        assertTrue(restored.isPresent());
        assertEquals("run-1", restored.get().runId());
        assertNull(restored.get().appliedAt());
        assertEquals(1, restored.get().matches().size());
        assertEquals("high", restored.get().matches().get(0).confidence());
        assertEquals(1, restored.get().matches().get(0).sources().size());
        assertEquals("https://example.com/spec-sheet", restored.get().matches().get(0).sources().get(0).url());
    }

    @Test
    void latestForReturnsTheMostRecentRunNotTheFirst() {
        JdbcDeviceDiscoveryStore store = newStore();
        Instant earlier = Instant.now().minusSeconds(60);
        Instant later = Instant.now();
        store.save(new DeviceDiscoveryResult("run-old", "device-x", earlier, null, List.of()));
        store.save(new DeviceDiscoveryResult("run-new", "device-x", later, null, List.of()));

        Optional<DeviceDiscoveryResult> latest = store.latestFor("device-x");

        assertTrue(latest.isPresent());
        assertEquals("run-new", latest.get().runId());
    }

    @Test
    void markAppliedPersistsAcrossInstances() {
        JdbcDeviceDiscoveryStore first = newStore();
        first.save(new DeviceDiscoveryResult("run-apply", "device-y", Instant.now(), null, List.of()));

        Instant appliedAt = Instant.now();
        first.markApplied("run-apply", appliedAt);

        JdbcDeviceDiscoveryStore restarted = newStore();
        Optional<DeviceDiscoveryResult> restored = restarted.latestFor("device-y");

        assertTrue(restored.isPresent());
        assertNotNull(restored.get().appliedAt());
    }
}
