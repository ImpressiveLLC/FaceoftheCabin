package com.cabin.orchestrator.locations;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Same Testcontainers-against-real-Postgres pattern as
 * EventPipelineIntegrationTest -- HubLocationService's CREATE TABLE IF NOT
 * EXISTS + seed-if-empty + ON CONFLICT DO UPDATE SQL is Postgres-specific
 * (matches FamilyProfileService, which this class was modeled on), so a
 * real database is what actually proves it, not a mock JdbcTemplate.
 *
 * @Value-injected fields (the cabin.locations.* defaults) never run outside
 * a real Spring context, so this test sets them via ReflectionTestUtils --
 * same technique EventPipelineIntegrationTest already uses for
 * EventPublisher.bootstrapServers -- rather than relying on Spring Boot's
 * property resolution.
 */
@Testcontainers
class HubLocationServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private HubLocationService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        // Fresh table per test -- init()'s seedIfEmpty() only seeds when
        // the table is empty, and Testcontainers reuses the same container
        // across @Test methods within this class by default.
        jdbc.execute("DROP TABLE IF EXISTS hub_locations");

        service = new HubLocationService(jdbc);
        ReflectionTestUtils.setField(service, "cabinApiBase", "http://cabin-hub:8090");
        ReflectionTestUtils.setField(service, "cabinWsBase", "ws://cabin-hub:9001");
        ReflectionTestUtils.setField(service, "cabinGrafanaUrl", "http://cabin-hub:3002");
        ReflectionTestUtils.setField(service, "cabinNoderedUrl", "http://cabin-hub:1880");
        ReflectionTestUtils.setField(service, "cabinHaUrl", "http://cabin-hub:8123");
        ReflectionTestUtils.setField(service, "cabinFrigateUrl", "http://cabin-hub:5000");
        ReflectionTestUtils.setField(service, "cabinZ2mUrl", "http://cabin-hub:8080");
        ReflectionTestUtils.setField(service, "cabinFamilyHubUrl", "");
        ReflectionTestUtils.setField(service, "homeApiBase", "http://home-hub:8080");
        ReflectionTestUtils.setField(service, "homeWsBase", "ws://home-hub:9001");
        ReflectionTestUtils.setField(service, "homeGrafanaUrl", "http://home-hub:3000");
        ReflectionTestUtils.setField(service, "homeNoderedUrl", "http://home-hub:1880");
        ReflectionTestUtils.setField(service, "homeHaUrl", "http://home-hub:8123");
        ReflectionTestUtils.setField(service, "homeFrigateUrl", "http://home-hub:5000");
        ReflectionTestUtils.setField(service, "homeFamilyHubUrl", "");
        service.init(); // @PostConstruct doesn't fire outside a Spring context
    }

    @Test
    void seedsCabinAndHomeOnFreshTable() {
        List<HubLocation> all = service.list();
        assertThat(all).hasSize(2);
        assertThat(all.get(0).id()).isEqualTo("cabin");
        assertThat(all.get(0).apiBase()).isEqualTo("http://cabin-hub:8090");
        assertThat(all.get(1).id()).isEqualTo("home");
        assertThat(all.get(1).apiBase()).isEqualTo("http://home-hub:8080");
    }

    @Test
    void initIsIdempotentAndDoesNotReseedOnRestart() {
        service.init(); // simulates a second app startup against the same table
        assertThat(service.list()).hasSize(2);
    }

    @Test
    void createAddsANewLocationWithNextSortOrder() {
        HubLocation lakehouse = new HubLocation(
            "lakehouse", "Lake House", "http://lakehouse-hub:8080", null,
            "http://lakehouse-hub:3000", null, null, null, null, null,
            0, true, 0, 0);

        service.create(lakehouse);

        List<HubLocation> all = service.list();
        assertThat(all).hasSize(3);
        HubLocation created = all.stream().filter(l -> l.id().equals("lakehouse")).findFirst().orElseThrow();
        assertThat(created.label()).isEqualTo("Lake House");
        assertThat(created.sortOrder()).isEqualTo(2); // after cabin(0), home(1)
    }

    @Test
    void createIsUpsertWhenIdAlreadyExists() {
        HubLocation updatedCabin = new HubLocation(
            "cabin", "Cabin Renamed", "http://new-cabin-url:9999", null,
            null, null, null, null, null, null, 0, true, 0, 0);

        service.create(updatedCabin);

        HubLocation result = service.byId("cabin");
        assertThat(result.label()).isEqualTo("Cabin Renamed");
        assertThat(result.apiBase()).isEqualTo("http://new-cabin-url:9999");
        assertThat(service.list()).hasSize(2); // still 2, not a duplicate row
    }

    @Test
    void updateMergesOnlyNonNullPatchFields() {
        HubLocation patch = new HubLocation(
            "cabin", null, null, null,
            "http://new-grafana:3000", null, null, null, null, null,
            0, true, 0, 0);

        HubLocation result = service.update("cabin", patch);

        assertThat(result.grafanaUrl()).isEqualTo("http://new-grafana:3000"); // patched
        assertThat(result.apiBase()).isEqualTo("http://cabin-hub:8090"); // untouched, still the seeded value
        assertThat(result.label()).isEqualTo("Cabin"); // untouched
    }

    @Test
    void updateReturnsNullForUnknownId() {
        HubLocation patch = new HubLocation("nope", "X", null, null, null, null, null, null, null, null, 0, true, 0, 0);
        assertThat(service.update("nope", patch)).isNull();
    }

    @Test
    void archiveExcludesFromListButByIdStillResolves() {
        boolean archived = service.archive("home");

        assertThat(archived).isTrue();
        assertThat(service.list()).extracting(HubLocation::id).containsExactly("cabin");
        HubLocation stillResolvable = service.byId("home");
        assertThat(stillResolvable).isNotNull();
        assertThat(stillResolvable.active()).isFalse();
    }

    @Test
    void archiveOfUnknownIdReturnsFalse() {
        assertThat(service.archive("nope")).isFalse();
    }

    @Test
    void reorderUpdatesSortOrderToMatchGivenSequence() {
        service.reorder(List.of("home", "cabin"));

        List<HubLocation> all = service.list();
        assertThat(all).extracting(HubLocation::id).containsExactly("home", "cabin");
    }
}
