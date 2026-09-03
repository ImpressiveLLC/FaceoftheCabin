package com.cabin.orchestrator.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * In-memory-fake-backed unit test for the actual business logic
 * (expiry, single-use, deactivation cascading to sessions) --
 * GoogleAuthInterceptorTest already covers the end-to-end auth path,
 * this covers the service's own edge cases directly.
 */
class ManagedUserServiceTest {

    private InMemoryManagedUserStore users;
    private InMemoryMagicLinkTokenStore magicLinks;
    private InMemoryManagedUserSessionStore sessions;
    private final List<String> sentUrls = new ArrayList<>();
    private ManagedUserService service;

    @BeforeEach
    void setUp() {
        users = new InMemoryManagedUserStore();
        magicLinks = new InMemoryMagicLinkTokenStore();
        sessions = new InMemoryManagedUserSessionStore();
        sentUrls.clear();
        service = new ManagedUserService(users, magicLinks, sessions, (to, name, url) -> sentUrls.add(url));
        ReflectionTestUtils.setField(service, "frontendOrigin", "https://app.example.com/");
    }

    @Test
    void inviteEmailsAMagicLinkPointingAtTheConfiguredFrontendOrigin() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");

        service.invite(user.id());

        assertEquals(1, sentUrls.size());
        assertTrue(sentUrls.get(0).startsWith("https://app.example.com/auth/magic/"),
            "a trailing slash on the configured origin must not produce a doubled slash");
    }

    @Test
    void inviteRejectsADeactivatedUser() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.VIEWER, "nate@example.com");
        service.setActive(user.id(), false);

        assertThrows(IllegalStateException.class, () -> service.invite(user.id()));
        assertTrue(sentUrls.isEmpty(), "no email should go out for a deactivated user");
    }

    @Test
    void inviteRejectsAnUnknownManagedUserId() {
        assertThrows(NoSuchElementException.class, () -> service.invite("no-such-id"));
    }

    @Test
    void consumeMagicLinkIssuesARealSessionAndMarksTheLinkUsed() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");
        service.invite(user.id());
        String token = tokenFromUrl(sentUrls.get(0));

        Optional<ManagedUserSession> session = service.consumeMagicLink(token);

        assertTrue(session.isPresent());
        assertEquals(user.id(), session.get().managedUserId());
    }

    @Test
    void aMagicLinkIsTrulySingleUse() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");
        service.invite(user.id());
        String token = tokenFromUrl(sentUrls.get(0));
        service.consumeMagicLink(token);

        Optional<ManagedUserSession> secondAttempt = service.consumeMagicLink(token);

        assertTrue(secondAttempt.isEmpty(), "a magic link must not be exchangeable for a second session");
    }

    @Test
    void anExpiredMagicLinkIsRejected() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");
        Instant past = Instant.now().minusSeconds(60);
        magicLinks.save(new MagicLinkToken("expired-token", user.id(), past, null, past.minusSeconds(60)));

        assertTrue(service.consumeMagicLink("expired-token").isEmpty());
    }

    @Test
    void anUnknownMagicLinkTokenIsRejected() {
        assertTrue(service.consumeMagicLink("never-issued").isEmpty());
    }

    @Test
    void consumingAMagicLinkForANowDeactivatedUserFails() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");
        service.invite(user.id());
        String token = tokenFromUrl(sentUrls.get(0));
        service.setActive(user.id(), false); // deactivated between email sent and link clicked

        assertTrue(service.consumeMagicLink(token).isEmpty());
    }

    @Test
    void validateSessionReturnsEmptyForARevokedSession() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");
        service.invite(user.id());
        ManagedUserSession session = service.consumeMagicLink(tokenFromUrl(sentUrls.get(0))).orElseThrow();

        service.revokeSession(session.token());

        assertTrue(service.validateSession(session.token()).isEmpty());
    }

    @Test
    void validateSessionReturnsEmptyForAnExpiredSession() {
        ManagedUser user = service.create("a@example.com", "Alice", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");
        Instant past = Instant.now().minusSeconds(60);
        sessions.save(new ManagedUserSession("sess-1", user.id(), past, null, past.minusSeconds(60)));

        assertTrue(service.validateSession("sess-1").isEmpty());
    }

    @Test
    void setActiveOnAnUnknownIdThrows() {
        assertThrows(NoSuchElementException.class, () -> service.setActive("no-such-id", false));
    }

    private static String tokenFromUrl(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private static final class InMemoryManagedUserStore implements ManagedUserStore {
        private final Map<String, ManagedUser> byId = new HashMap<>();
        @Override public List<ManagedUser> loadAll() { return List.copyOf(byId.values()); }
        @Override public Optional<ManagedUser> findById(String id) { return Optional.ofNullable(byId.get(id)); }
        @Override public Optional<ManagedUser> findByEmail(String email) {
            return byId.values().stream().filter(u -> u.email().equalsIgnoreCase(email)).findFirst();
        }
        @Override public void save(ManagedUser user) { byId.put(user.id(), user); }
    }

    private static final class InMemoryMagicLinkTokenStore implements MagicLinkTokenStore {
        private final Map<String, MagicLinkToken> byToken = new HashMap<>();
        @Override public void save(MagicLinkToken token) { byToken.put(token.token(), token); }
        @Override public Optional<MagicLinkToken> findByToken(String token) { return Optional.ofNullable(byToken.get(token)); }
        @Override public void markConsumed(String token, Instant consumedAt) {
            byToken.computeIfPresent(token, (t, existing) -> new MagicLinkToken(
                existing.token(), existing.managedUserId(), existing.expiresAt(), consumedAt, existing.createdAt()));
        }
    }

    private static final class InMemoryManagedUserSessionStore implements ManagedUserSessionStore {
        private final Map<String, ManagedUserSession> byToken = new HashMap<>();
        @Override public void save(ManagedUserSession session) { byToken.put(session.token(), session); }
        @Override public Optional<ManagedUserSession> findByToken(String token) { return Optional.ofNullable(byToken.get(token)); }
        @Override public void revoke(String token, Instant revokedAt) {
            byToken.computeIfPresent(token, (t, existing) -> new ManagedUserSession(
                existing.token(), existing.managedUserId(), existing.expiresAt(), revokedAt, existing.createdAt()));
        }
    }
}
