package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-Postgres round-trip for all three Sprint 4 tables together (they're
 * used together in practice -- a session always traces back through a
 * magic link to a managed user), same Testcontainers pattern as
 * JdbcCabinAccessTokenStoreIntegrationTest.
 */
@Testcontainers
class ManagedUserPersistenceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    @Test
    void aSavedManagedUserSurvivesANewStoreInstance() {
        JdbcTemplate jdbc = jdbc();
        JdbcManagedUserStore first = new JdbcManagedUserStore(jdbc);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        ManagedUser user = new ManagedUser("mu-1", "alice@example.com", "Alice",
            ManagedUserRole.HOUSEHOLD_MEMBER, true, "nate@example.com", now);

        first.save(user);
        JdbcManagedUserStore restarted = new JdbcManagedUserStore(jdbc);
        Optional<ManagedUser> restored = restarted.findByEmail("ALICE@EXAMPLE.COM"); // case-insensitive lookup

        assertTrue(restored.isPresent());
        assertEquals("mu-1", restored.get().id());
        assertEquals(ManagedUserRole.HOUSEHOLD_MEMBER, restored.get().role());
        assertTrue(restored.get().active());
    }

    @Test
    void savingAgainWithTheSameIdUpdatesRatherThanDuplicates() {
        // The container/table is shared across every @Test in this class
        // (static @Container), so this deliberately checks the ON CONFLICT
        // update took effect via a scoped findById() rather than a total
        // loadAll().size() -- a bare count would depend on which other
        // tests in this file happened to run first, since id "mu-2" is
        // unique but the PRIMARY KEY constraint alone already guarantees no
        // literal duplicate row could exist for it either way.
        JdbcTemplate jdbc = jdbc();
        JdbcManagedUserStore store = new JdbcManagedUserStore(jdbc);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        store.save(new ManagedUser("mu-2", "bob@example.com", "Bob", ManagedUserRole.VIEWER, true, "nate@example.com", now));

        store.save(new ManagedUser("mu-2", "bob@example.com", "Bob", ManagedUserRole.VIEWER, false, "nate@example.com", now));

        assertFalse(store.findById("mu-2").orElseThrow().active());
    }

    @Test
    void aMagicLinkTokenSurvivesANewStoreInstanceAndCanBeMarkedConsumed() {
        JdbcTemplate jdbc = jdbc();
        JdbcMagicLinkTokenStore first = new JdbcMagicLinkTokenStore(jdbc);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        first.save(new MagicLinkToken("link-1", "mu-1", now.plusSeconds(1800), null, now));

        JdbcMagicLinkTokenStore restarted = new JdbcMagicLinkTokenStore(jdbc);
        Optional<MagicLinkToken> restored = restarted.findByToken("link-1");
        assertTrue(restored.isPresent());
        assertNull(restored.get().consumedAt());

        restarted.markConsumed("link-1", now.plusSeconds(60));
        assertNotNull(restarted.findByToken("link-1").orElseThrow().consumedAt());
    }

    @Test
    void aManagedUserSessionSurvivesANewStoreInstanceAndCanBeRevoked() {
        JdbcTemplate jdbc = jdbc();
        JdbcManagedUserSessionStore first = new JdbcManagedUserSessionStore(jdbc);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        first.save(new ManagedUserSession("sess-1", "mu-1", now.plusSeconds(7776000), null, now));

        JdbcManagedUserSessionStore restarted = new JdbcManagedUserSessionStore(jdbc);
        Optional<ManagedUserSession> restored = restarted.findByToken("sess-1");
        assertTrue(restored.isPresent());
        assertTrue(restored.get().isValid(now.plusSeconds(60)));

        restarted.revoke("sess-1", now.plusSeconds(120));
        assertFalse(restarted.findByToken("sess-1").orElseThrow().isValid(now.plusSeconds(180)));
    }
}
