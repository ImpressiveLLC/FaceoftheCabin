package com.cabin.orchestrator.devices.monitoring;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class MonitoringPreferenceDatabaseTest {
    @Container static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcTemplate jdbc;
    SimpleDriverDataSource dataSource;
    DeviceRegistry registry;
    MonitoringPreferenceService service;

    @BeforeEach void setup() {
        dataSource = new SimpleDriverDataSource(new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        registry = mock(DeviceRegistry.class);
        for (String id : List.of("a", "b", "c")) when(registry.descriptor(id)).thenReturn(Optional.of(
            new DeviceDescriptor(id, id, DeviceType.POWER_METER, Set.of(), "fixture", "", false, "cabin")));
        service = newService();
        service.init();
        jdbc.execute("TRUNCATE monitoring_card_preference, monitoring_preference_revision, monitoring_preference_audit");
    }
    MonitoringPreferenceService newService() {
        return new MonitoringPreferenceService(jdbc, new DataSourceTransactionManager(dataSource), registry, new ObjectMapper());
    }
    MonitoringPreferences.Card card(String id, boolean included) { return new MonitoringPreferences.Card(id, included); }

    @Test void readDoesNotWriteAndBackfillPreservesExplicitChoicesAcrossServiceRestart() {
        assertEquals(0, service.read("cabin").revision());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitoring_preference_revision", Integer.class));
        service.backfill("cabin", List.of("a", "b"), "compatibility-fixture");
        service.replace("cabin", 1, List.of(card("b", false), card("a", true)), "member@example.test");
        var result = newService().backfill("cabin", List.of("a", "b", "c"), "compatibility-fixture");
        assertEquals(List.of(card("b", false), card("a", true), card("c", true)), result.cards());
        assertEquals(result, service.backfill("cabin", List.of("a", "b", "c"), "compatibility-fixture"));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM monitoring_preference_audit", Integer.class));
    }

    @Test void revisionConflictCannotOverwriteAnotherBrowser() {
        var first = service.replace("cabin", 0, List.of(card("a", false)), "one@example.test");
        assertEquals(409, assertThrows(ResponseStatusException.class, () ->
            service.replace("cabin", 0, List.of(card("a", true)), "two@example.test")).getStatusCode().value());
        assertEquals(first, service.read("cabin"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitoring_preference_audit", Integer.class));
    }

    @Test void partialUpdateDoesNotEraseHiddenChoiceOrStalePreference() {
        service.replace("cabin", 0, List.of(card("a", false), card("b", true)), "member");
        when(registry.descriptor("a")).thenReturn(Optional.empty());
        var result = service.replace("cabin", 1, List.of(card("b", true)), "member");
        assertEquals(List.of(card("b", true), card("a", false)), result.cards());
    }

    @Test void simultaneousFirstWritersCannotBothCommitRevisionOne() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> writer = () -> {
                start.await();
                try { service.replace("cabin", 0, List.of(card("a", true)), "member"); return 200; }
                catch (ResponseStatusException e) { return e.getStatusCode().value(); }
            };
            var one = pool.submit(writer);
            var two = pool.submit(writer);
            start.countDown();
            assertEquals(Set.of(200, 409), Set.of(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS)));
        }
        assertEquals(1, service.read("cabin").revision());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM monitoring_preference_audit", Integer.class));
    }

    @Test void auditFailureRollsBackCardsAndRevision() {
        var before = service.replace("cabin", 0, List.of(card("a", false)), "first");
        jdbc.execute("ALTER TABLE monitoring_preference_audit ADD CONSTRAINT test_actor CHECK (actor <> 'reject')");
        try {
            assertThrows(RuntimeException.class, () -> service.replace("cabin", 1, List.of(card("b", true)), "reject"));
            assertEquals(before, service.read("cabin"));
        } finally { jdbc.execute("ALTER TABLE monitoring_preference_audit DROP CONSTRAINT test_actor"); }
    }

    @Test void invalidIdentityLocationAndDuplicatesDoNotInitializePersistence() {
        for (var cards : List.of(List.of(card("unknown", true)), List.of(card("a", true), card("a", false))))
            assertThrows(ResponseStatusException.class, () -> service.replace("cabin", 0, cards, "member"));
        assertThrows(ResponseStatusException.class, () -> service.replace("home", 0, List.of(card("a", true)), "member"));
        assertThrows(ResponseStatusException.class, () -> service.read("both"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM monitoring_preference_revision", Integer.class));
    }
}
