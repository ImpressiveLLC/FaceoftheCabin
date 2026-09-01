package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class JdbcCabinAccessTokenStoreIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcCabinAccessTokenStore newStore() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcCabinAccessTokenStore(jdbc);
    }

    @Test
    void aSavedTokenSurvivesANewStoreInstanceWithItsFullScope() {
        JdbcCabinAccessTokenStore first = newStore();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        CabinAccessToken token = new CabinAccessToken(
            "tok-1", "secret-abc", "Insurance Claim Sep 2026",
            List.of("dashboard", "device_states", "alerts_read"),
            now.plusSeconds(3600), null, "nate@example.com", now);

        first.save(token);
        JdbcCabinAccessTokenStore restarted = newStore();
        Optional<CabinAccessToken> restored = restarted.findByToken("secret-abc");

        assertTrue(restored.isPresent());
        assertEquals("tok-1", restored.get().id());
        assertEquals("Insurance Claim Sep 2026", restored.get().label());
        assertEquals(List.of("dashboard", "device_states", "alerts_read"), restored.get().scope());
        assertEquals(now.plusSeconds(3600), restored.get().expiresAt());
        assertNull(restored.get().revokedAt());
    }

    @Test
    void revokeIsAPersistedSoftDeleteNotARealDelete() {
        JdbcCabinAccessTokenStore store = newStore();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        store.save(new CabinAccessToken("tok-2", "secret-def", "Contractor", List.of("device_states"),
            null, null, "nate@example.com", now));

        store.revoke("tok-2", now.plusSeconds(60));

        List<CabinAccessToken> all = store.loadAll();
        assertEquals(1, all.size(), "revoke must not delete the row -- history stays queryable");
        assertEquals(now.plusSeconds(60), all.get(0).revokedAt());
        assertFalse(all.get(0).isValid(now.plusSeconds(120)), "a revoked token must never validate as active again");
    }

    @Test
    void findByTokenReturnsEmptyForAnUnknownToken() {
        JdbcCabinAccessTokenStore store = newStore();
        assertTrue(store.findByToken("never-issued").isEmpty());
    }
}
