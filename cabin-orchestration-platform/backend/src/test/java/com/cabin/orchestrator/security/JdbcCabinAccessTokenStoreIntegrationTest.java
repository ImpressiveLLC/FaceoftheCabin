package com.cabin.orchestrator.security;

import org.junit.jupiter.api.BeforeEach;
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

    private JdbcTemplate newJdbc() {
        return new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private JdbcCabinAccessTokenStore newStore() {
        return new JdbcCabinAccessTokenStore(newJdbc());
    }

    /** The container is shared by every test in this class, and some tests count rows, so each starts from empty tables. */
    @BeforeEach
    void emptyTables() {
        newStore(); // creates the tables on a first run
        JdbcTemplate jdbc = newJdbc();
        jdbc.execute("DELETE FROM cabin_access_events");
        jdbc.execute("DELETE FROM cabin_access_tokens");
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

    // ---- Access link usage tracking (HO-2026-10-29) ----

    private JdbcCabinAccessTokenStore storeWithLink(String id, String secret) {
        JdbcCabinAccessTokenStore store = newStore();
        store.save(new CabinAccessToken(id, secret, "Agent preview", List.of("demo"), null, null,
            "nate@example.com", Instant.now().truncatedTo(ChronoUnit.SECONDS)));
        return store;
    }

    @Test
    void twoIdenticalRequestsInOneFiveMinuteBucketMakeOneRowWithTwoHits() {
        JdbcCabinAccessTokenStore store = storeWithLink("tok-use-1", "secret-use-1");
        Instant t = Instant.parse("2026-10-29T12:01:10Z");

        store.recordUse("tok-use-1", t, "demo", "/api/alerts/active", "ALLOW", 200, "198.51.100.7", "UA", false);
        store.recordUse("tok-use-1", t.plusSeconds(20), "demo", "/api/alerts/active", "ALLOW", 200, "198.51.100.7", "UA", false);

        List<AccessEvent> events = store.events("tok-use-1", t.minusSeconds(60), 50);
        assertEquals(1, events.size());
        assertEquals(2, events.get(0).hits());
        assertEquals(t.plusSeconds(20), events.get(0).occurredAt());
        assertEquals("tok-use-1", events.get(0).tokenId());
    }

    @Test
    void aDifferentBucketOrStatusIsAnotherRowAndEventsComeBackNewestFirst() {
        JdbcCabinAccessTokenStore store = storeWithLink("tok-use-2", "secret-use-2");
        Instant t = Instant.parse("2026-10-29T12:01:10Z");

        store.recordUse("tok-use-2", t, "demo", "/api/alerts/active", "ALLOW", 200, "ip", "UA", false);
        store.recordUse("tok-use-2", t.plusSeconds(400), "demo", "/api/alerts/active", "ALLOW", 200, "ip", "UA", false);
        store.recordUse("tok-use-2", t.plusSeconds(410), "demo", "/api/camera/list", "DENY", 403, "ip", "UA", false);

        List<AccessEvent> events = store.events("tok-use-2", t.minusSeconds(60), 50);
        assertEquals(3, events.size());
        assertEquals(403, events.get(0).status());
        assertEquals(t.plusSeconds(400), events.get(1).occurredAt());
    }

    @Test
    void theSummaryCountsVisitsNotRequests() {
        JdbcCabinAccessTokenStore store = storeWithLink("tok-use-3", "secret-use-3");
        Instant t = Instant.parse("2026-10-29T12:00:00Z");

        store.recordUse("tok-use-3", t, "demo", "page_open", "ALLOW", 200, "ip", "UA", true);
        store.recordUse("tok-use-3", t.plusSeconds(30), "demo", "/api/devices", "ALLOW_REDACT", 200, "ip", "UA", false);
        store.recordUse("tok-use-3", t.plusSeconds(60), "demo", "/api/alerts/active", "ALLOW", 200, "ip", "UA", false);
        // after a quiet gap longer than 30 minutes: a new visit
        store.recordUse("tok-use-3", t.plusSeconds(3 * 3600), "demo", "/api/devices", "ALLOW_REDACT", 200, "ip", "UA", false);

        AccessUsage usage = store.usage().get("tok-use-3");
        assertNotNull(usage);
        assertEquals(t, usage.firstUsedAt());
        assertEquals(t.plusSeconds(3 * 3600), usage.lastUsedAt());
        assertEquals(2, usage.useCount());
        assertNull(store.usage().get("never-used"));
    }

    @Test
    void purgeRemovesEventsOlderThanTheCutoffAndKeepsTheRest() {
        JdbcCabinAccessTokenStore store = storeWithLink("tok-use-4", "secret-use-4");
        Instant now = Instant.parse("2026-10-29T12:00:00Z");
        store.recordUse("tok-use-4", now.minus(100, ChronoUnit.DAYS), "demo", "/old", "ALLOW", 200, "ip", "UA", false);
        store.recordUse("tok-use-4", now.minus(10, ChronoUnit.DAYS), "demo", "/recent", "ALLOW", 200, "ip", "UA", false);

        int removed = store.purgeEventsBefore(now.minus(90, ChronoUnit.DAYS));

        assertEquals(1, removed);
        List<AccessEvent> left = store.events("tok-use-4", now.minus(365, ChronoUnit.DAYS), 50);
        assertEquals(1, left.size());
        assertEquals("/recent", left.get(0).route());
    }
}
