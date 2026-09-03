package com.cabin.orchestrator.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** In-memory-store-backed, matching CabinAccessTokensControllerTest's own pattern -- persistence itself is covered elsewhere. */
class ManagedUsersControllerTest {

    private final InMemoryManagedUserStore userStore = new InMemoryManagedUserStore();
    private final InMemoryMagicLinkTokenStore magicLinkStore = new InMemoryMagicLinkTokenStore();
    private final InMemoryManagedUserSessionStore sessionStore = new InMemoryManagedUserSessionStore();
    private final List<String> sentUrls = new ArrayList<>();
    private final ManagedUserService service =
        new ManagedUserService(userStore, magicLinkStore, sessionStore, (to, name, url) -> sentUrls.add(url));
    private final ManagedUsersController controller = new ManagedUsersController(service);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "frontendOrigin", "https://app.example.com");
    }

    private MockHttpServletRequest signedInAs(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, email);
        return request;
    }

    @Test
    void createRecordsWhichAdminEnrolledTheUser() {
        ManagedUser created = controller.create(
            Map.of("email", "alice@example.com", "name", "Alice", "role", "HOUSEHOLD_MEMBER"),
            signedInAs("nate@example.com"));

        assertThat(created.createdBy()).isEqualTo("nate@example.com");
        assertThat(created.email()).isEqualTo("alice@example.com");
        assertThat(created.role()).isEqualTo(ManagedUserRole.HOUSEHOLD_MEMBER);
        assertThat(created.active()).isTrue();
    }

    @Test
    void createRejectsAMissingEmail() {
        assertThatThrownBy(() -> controller.create(Map.of("name", "Alice", "role", "VIEWER"), signedInAs("nate@example.com")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsAMissingName() {
        assertThatThrownBy(() -> controller.create(Map.of("email", "a@example.com", "role", "VIEWER"), signedInAs("nate@example.com")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsAnInvalidRole() {
        assertThatThrownBy(() -> controller.create(
            Map.of("email", "a@example.com", "name", "Alice", "role", "SUPERADMIN"), signedInAs("nate@example.com")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deactivateThenReactivateRoundTrips() {
        ManagedUser created = controller.create(
            Map.of("email", "a@example.com", "name", "Alice", "role", "VIEWER"), signedInAs("nate@example.com"));

        Map<String, Object> deactivated = controller.deactivate(created.id());
        assertThat(deactivated.get("active")).isEqualTo(false);
        assertThat(service.list().get(0).active()).isFalse();

        Map<String, Object> reactivated = controller.reactivate(created.id());
        assertThat(reactivated.get("active")).isEqualTo(true);
    }

    @Test
    void deactivateOnAnUnknownIdReturnsAnErrorNotAnException() {
        Map<String, Object> result = controller.deactivate("no-such-id");
        assertThat(result).containsKey("error");
    }

    @Test
    void invitePostsToTheEmailSenderAndReportsSent() {
        ManagedUser created = controller.create(
            Map.of("email", "a@example.com", "name", "Alice", "role", "VIEWER"), signedInAs("nate@example.com"));

        Map<String, Object> result = controller.invite(created.id());

        assertThat(result.get("sent")).isEqualTo(true);
        assertThat(sentUrls).hasSize(1);
    }

    @Test
    void inviteOnADeactivatedUserReturnsAnErrorNotAnException() {
        ManagedUser created = controller.create(
            Map.of("email", "a@example.com", "name", "Alice", "role", "VIEWER"), signedInAs("nate@example.com"));
        controller.deactivate(created.id());

        Map<String, Object> result = controller.invite(created.id());

        assertThat(result).containsKey("error");
        assertThat(sentUrls).isEmpty();
    }

    @Test
    void consumeMagicLinkReturnsARealSessionShape() {
        ManagedUser created = controller.create(
            Map.of("email", "a@example.com", "name", "Alice", "role", "HOUSEHOLD_MEMBER"), signedInAs("nate@example.com"));
        controller.invite(created.id());
        String token = sentUrls.get(0).substring(sentUrls.get(0).lastIndexOf('/') + 1);

        Map<String, Object> result = controller.consumeMagicLink(token);

        assertThat(result).containsKeys("sessionToken", "email", "name", "role");
        assertThat(result.get("email")).isEqualTo("a@example.com");
        assertThat(result.get("role")).isEqualTo("HOUSEHOLD_MEMBER");
    }

    @Test
    void consumeMagicLinkReturnsAnErrorForAnInvalidToken() {
        Map<String, Object> result = controller.consumeMagicLink("not-a-real-token");

        assertThat(result).containsKey("error");
        assertThat(result).doesNotContainKey("sessionToken");
    }

    @Test
    void listReturnsEveryEnrolledUserIncludingDeactivatedOnes() {
        ManagedUser created = controller.create(
            Map.of("email", "a@example.com", "name", "Alice", "role", "VIEWER"), signedInAs("nate@example.com"));
        controller.deactivate(created.id());

        assertThat(controller.list()).hasSize(1);
        assertThat(controller.list().get(0).active()).isFalse();
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
