package com.cabin.orchestrator.opportunities;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Postgres (Testcontainers) for cabin_event + optimization_opportunity,
 * a real in-memory DeviceRegistry (no persistence needed -- registerDescriptor
 * defaults a fresh descriptor to ASSIGNED, which is in scope). See
 * OptimizationAnalyticsService's own javadoc for why POWER_DRAW_ANOMALY,
 * not the handover's original ENERGY_IDLE spec.
 */
@Testcontainers
class OptimizationAnalyticsServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String DEVICE_ID = "z2m-heater_mech_room";

    private CabinEventService eventService;
    private JdbcOptimizationOpportunityStore store;
    private DeviceRegistry registry;
    private OptimizationAnalyticsService job;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        jdbc.execute("DROP TABLE IF EXISTS device_reporting_relationship");
        jdbc.execute("DROP TABLE IF EXISTS optimization_opportunity");

        eventService = new CabinEventService(jdbc);
        store = new JdbcOptimizationOpportunityStore(jdbc, new ObjectMapper().findAndRegisterModules());
        registry = new DeviceRegistry(List.of());
        job = new OptimizationAnalyticsService(registry, eventService, store);
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "continuousDrawHours", 48);
        ReflectionTestUtils.setField(job, "lookbackDays", 30);
        ReflectionTestUtils.setField(job, "offThresholdWatts", 1.0);

        registerPowerMeter(DEVICE_ID, DeviceType.POWER_METER);
    }

    private void registerPowerMeter(String deviceId, DeviceType type) {
        registry.registerDescriptor(new DeviceDescriptor(
            deviceId, deviceId, type, Set.of(DeviceCapability.TELEMETRY, DeviceCapability.POWER_MONITOR),
            "mqtt", deviceId, true, "cabin"));
        registry.update(new DeviceStatus(deviceId, type, deviceId, "ONLINE",
            Instant.now(), Map.of("power", 42.0), "cabin"));
    }

    private void telemetry(String deviceId, double power, Instant time) {
        eventService.save(new CabinEvent(UUID.randomUUID().toString(), deviceId, "TELEMETRY", "INFO",
            time, Map.of("power", power)));
    }

    private DeviceStatus statusFor(String deviceId) {
        return registry.get(deviceId);
    }

    @Test
    void flagsADeviceDrawingPowerContinuouslyPastTheThreshold() {
        telemetry(DEVICE_ID, 40.0, Instant.now().minusSeconds(60 * 3600)); // 60h ago
        telemetry(DEVICE_ID, 41.0, Instant.now().minusSeconds(30 * 3600)); // 30h ago
        telemetry(DEVICE_ID, 42.0, Instant.now().minusSeconds(3600));      // 1h ago -- always on, never near zero

        job.checkPowerDrawAnomaly(statusFor(DEVICE_ID));

        Optional<OptimizationOpportunity> found = store.findOpenOrAcknowledged(DEVICE_ID, OpportunityType.POWER_DRAW_ANOMALY);
        assertThat(found).isPresent();
        assertThat(found.get().status()).isEqualTo(OpportunityStatus.OPEN);
        // JSONB round-trip deserializes a small JSON integer as Integer, not
        // Long, regardless of what type evidence.put() was called with --
        // Number.longValue() sidesteps the exact-boxed-type mismatch.
        assertThat(((Number) found.get().evidence().get("continuousHours")).longValue()).isGreaterThanOrEqualTo(48L);
    }

    @Test
    void doesNotFlagADeviceThatRecentlyDippedNearZero() {
        telemetry(DEVICE_ID, 40.0, Instant.now().minusSeconds(60 * 3600));
        telemetry(DEVICE_ID, 0.2, Instant.now().minusSeconds(2 * 3600)); // dipped near-zero 2h ago
        telemetry(DEVICE_ID, 42.0, Instant.now().minusSeconds(60));

        job.checkPowerDrawAnomaly(statusFor(DEVICE_ID));

        assertThat(store.findOpenOrAcknowledged(DEVICE_ID, OpportunityType.POWER_DRAW_ANOMALY)).isEmpty();
    }

    @Test
    void doesNotFlagADeviceWithNoTelemetryAtAllAndDoesNotThrow() {
        job.checkPowerDrawAnomaly(statusFor(DEVICE_ID));

        assertThat(store.findAll(null)).isEmpty();
    }

    @Test
    void reRunningTheJobRefreshesTheSameRowRatherThanDuplicating() {
        telemetry(DEVICE_ID, 40.0, Instant.now().minusSeconds(60 * 3600));
        job.checkPowerDrawAnomaly(statusFor(DEVICE_ID));
        String firstId = store.findOpenOrAcknowledged(DEVICE_ID, OpportunityType.POWER_DRAW_ANOMALY).orElseThrow().id();
        Instant firstDetectedAt = store.findById(firstId).orElseThrow().detectedAt();

        telemetry(DEVICE_ID, 45.0, Instant.now().minusSeconds(10)); // more recent telemetry, still no dip
        job.checkPowerDrawAnomaly(statusFor(DEVICE_ID));

        assertThat(store.findAll(null)).hasSize(1);
        OptimizationOpportunity refreshed = store.findById(firstId).orElseThrow();
        assertThat(refreshed.id()).isEqualTo(firstId);
        assertThat(refreshed.detectedAt()).isEqualTo(firstDetectedAt);
    }

    @Test
    void neverOverwritesAnAcknowledgedStatusBackToOpen() {
        telemetry(DEVICE_ID, 40.0, Instant.now().minusSeconds(60 * 3600));
        store.save(new OptimizationOpportunity("pre-acked", OpportunityType.POWER_DRAW_ANOMALY, DEVICE_ID,
            Instant.now().minusSeconds(90000), Map.of(), OpportunityStatus.ACKNOWLEDGED, null));

        job.checkPowerDrawAnomaly(statusFor(DEVICE_ID));

        assertThat(store.findAll(null)).hasSize(1);
        assertThat(store.findById("pre-acked").orElseThrow().status()).isEqualTo(OpportunityStatus.ACKNOWLEDGED);
    }

    @Test
    void aResolvedOpportunityGetsAFreshRowIfTheConditionRecurs() {
        telemetry(DEVICE_ID, 40.0, Instant.now().minusSeconds(60 * 3600));
        store.save(new OptimizationOpportunity("resolved-1", OpportunityType.POWER_DRAW_ANOMALY, DEVICE_ID,
            Instant.now().minusSeconds(90000), Map.of(), OpportunityStatus.RESOLVED, Instant.now().minusSeconds(80000)));

        job.checkPowerDrawAnomaly(statusFor(DEVICE_ID));

        assertThat(store.findAll(null)).hasSize(2);
        OptimizationOpportunity resolvedStillThere = store.findById("resolved-1").orElseThrow();
        assertThat(resolvedStillThere.status()).isEqualTo(OpportunityStatus.RESOLVED);
        Optional<OptimizationOpportunity> freshOpen = store.findOpenOrAcknowledged(DEVICE_ID, OpportunityType.POWER_DRAW_ANOMALY);
        assertThat(freshOpen).isPresent();
        assertThat(freshOpen.get().id()).isNotEqualTo("resolved-1");
    }

    @Test
    void scanForOpportunitiesSkipsNonPowerMeterDevices() {
        registerPowerMeter("z2m-not-a-plug", DeviceType.TEMPERATURE_SENSOR);
        telemetry("z2m-not-a-plug", 40.0, Instant.now().minusSeconds(60 * 3600));

        job.scanForOpportunities();

        assertThat(store.findAll(null)).isEmpty();
    }

    @Test
    void scanForOpportunitiesFlagsARealPowerMeterDeviceEndToEnd() {
        telemetry(DEVICE_ID, 40.0, Instant.now().minusSeconds(60 * 3600));

        job.scanForOpportunities();

        assertThat(store.findOpenOrAcknowledged(DEVICE_ID, OpportunityType.POWER_DRAW_ANOMALY)).isPresent();
    }

    @Test
    void doesNothingWhenDisabled() {
        ReflectionTestUtils.setField(job, "enabled", false);
        telemetry(DEVICE_ID, 40.0, Instant.now().minusSeconds(60 * 3600));

        job.scanForOpportunities();

        assertThat(store.findAll(null)).isEmpty();
    }
}
