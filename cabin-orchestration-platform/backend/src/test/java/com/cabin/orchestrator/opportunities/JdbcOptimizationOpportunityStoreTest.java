package com.cabin.orchestrator.opportunities;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Same Testcontainers-against-real-Postgres pattern as CabinEventServiceTest. */
@Testcontainers
class JdbcOptimizationOpportunityStoreTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcOptimizationOpportunityStore store;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS optimization_opportunity");
        store = new JdbcOptimizationOpportunityStore(jdbc, new ObjectMapper().findAndRegisterModules());
    }

    private OptimizationOpportunity opportunity(String id, String deviceId, OpportunityStatus status, Instant detectedAt) {
        return new OptimizationOpportunity(id, OpportunityType.POWER_DRAW_ANOMALY, deviceId, detectedAt,
            Map.of("continuousHours", 60L), status, status == OpportunityStatus.RESOLVED ? Instant.now() : null);
    }

    @Test
    void savedOpportunityRoundTripsExactly() {
        Instant detectedAt = Instant.now().minusSeconds(3600);
        store.save(opportunity("op-1", "z2m-heater_mech_room", OpportunityStatus.OPEN, detectedAt));

        OptimizationOpportunity loaded = store.findById("op-1").orElseThrow();

        assertThat(loaded.deviceId()).isEqualTo("z2m-heater_mech_room");
        assertThat(loaded.opportunityType()).isEqualTo(OpportunityType.POWER_DRAW_ANOMALY);
        assertThat(loaded.status()).isEqualTo(OpportunityStatus.OPEN);
        assertThat(loaded.evidence()).containsEntry("continuousHours", 60);
        assertThat(loaded.detectedAt()).isCloseTo(detectedAt, within3Seconds());
        assertThat(loaded.resolvedAt()).isNull();
    }

    @Test
    void savingTheSameIdAgainUpdatesInPlaceRatherThanDuplicating() {
        store.save(opportunity("op-2", "z2m-breaker_box", OpportunityStatus.OPEN, Instant.now()));
        store.save(new OptimizationOpportunity("op-2", OpportunityType.POWER_DRAW_ANOMALY, "z2m-breaker_box",
            Instant.now().minusSeconds(9999), Map.of("continuousHours", 90L), OpportunityStatus.ACKNOWLEDGED, null));

        assertThat(store.findAll(null)).hasSize(1);
        OptimizationOpportunity loaded = store.findById("op-2").orElseThrow();
        assertThat(loaded.status()).isEqualTo(OpportunityStatus.ACKNOWLEDGED);
        assertThat(loaded.evidence()).containsEntry("continuousHours", 90);
    }

    @Test
    void findAllFiltersByStatusAndSortsNewestFirst() {
        store.save(opportunity("old", "z2m-a", OpportunityStatus.RESOLVED, Instant.now().minusSeconds(200)));
        store.save(opportunity("mid", "z2m-b", OpportunityStatus.OPEN, Instant.now().minusSeconds(100)));
        store.save(opportunity("new", "z2m-c", OpportunityStatus.OPEN, Instant.now()));

        List<OptimizationOpportunity> open = store.findAll(OpportunityStatus.OPEN);

        assertThat(open).extracting(OptimizationOpportunity::id).containsExactly("new", "mid");
    }

    @Test
    void findAllWithNoFilterReturnsEveryStatus() {
        store.save(opportunity("a", "z2m-a", OpportunityStatus.OPEN, Instant.now()));
        store.save(opportunity("b", "z2m-b", OpportunityStatus.RESOLVED, Instant.now()));

        assertThat(store.findAll(null)).hasSize(2);
    }

    @Test
    void findByIdReturnsEmptyForAnUnknownId() {
        assertThat(store.findById("does-not-exist")).isEmpty();
    }

    @Test
    void findOpenOrAcknowledgedIgnoresResolvedRowsForTheSameDeviceAndType() {
        store.save(opportunity("resolved-1", "z2m-heater_mech_room", OpportunityStatus.RESOLVED, Instant.now().minusSeconds(500)));

        assertThat(store.findOpenOrAcknowledged("z2m-heater_mech_room", OpportunityType.POWER_DRAW_ANOMALY)).isEmpty();
    }

    @Test
    void findOpenOrAcknowledgedFindsAnAcknowledgedRowTooNotJustOpen() {
        store.save(opportunity("ack-1", "z2m-heater_mech_room", OpportunityStatus.ACKNOWLEDGED, Instant.now()));

        Optional<OptimizationOpportunity> found = store.findOpenOrAcknowledged("z2m-heater_mech_room", OpportunityType.POWER_DRAW_ANOMALY);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo("ack-1");
    }

    private org.assertj.core.data.TemporalUnitOffset within3Seconds() {
        return org.assertj.core.api.Assertions.within(3, java.time.temporal.ChronoUnit.SECONDS);
    }
}
