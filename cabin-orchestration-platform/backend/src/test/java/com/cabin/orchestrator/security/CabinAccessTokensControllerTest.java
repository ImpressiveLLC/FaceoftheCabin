package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In-memory store, matching GoogleAuthInterceptorTest's own fake -- this
 * controller has no query logic worth a real Postgres round-trip
 * (JdbcCabinAccessTokenStoreIntegrationTest already covers persistence).
 */
class CabinAccessTokensControllerTest {

    private final CabinAccessTokenStore store = new InMemoryStore();
    private final CabinAccessTokensController controller =
        new CabinAccessTokensController(new CabinAccessTokenService(store));

    private MockHttpServletRequest signedInAs(String email) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, email);
        return request;
    }

    @Test
    void createRecordsWhichAdminIssuedTheLink() {
        CabinAccessToken created = controller.create(
            Map.of("label", "Insurance Claim Sep 2026", "scope", List.of("dashboard", "device_states")),
            signedInAs("nate@example.com"));

        assertThat(created.createdBy()).isEqualTo("nate@example.com");
        assertThat(created.label()).isEqualTo("Insurance Claim Sep 2026");
        assertThat(created.scope()).containsExactly("dashboard", "device_states");
        assertThat(created.token()).isNotBlank();
    }

    @Test
    void createRejectsAMissingLabel() {
        assertThatThrownBy(() -> controller.create(Map.of("scope", List.of("dashboard")), signedInAs("nate@example.com")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void createRejectsAnEmptyScope() {
        assertThatThrownBy(() -> controller.create(Map.of("label", "No scope"), signedInAs("nate@example.com")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listReturnsEveryIssuedLinkIncludingRevokedOnes() {
        CabinAccessToken t = controller.create(Map.of("label", "Test", "scope", List.of("dashboard")), signedInAs("nate@example.com"));
        controller.revoke(t.id());

        List<CabinAccessToken> all = controller.list();

        assertThat(all).hasSize(1);
        assertThat(all.get(0).revokedAt()).isNotNull();
    }

    private static final class InMemoryStore implements CabinAccessTokenStore {
        private final Map<String, CabinAccessToken> byToken = new java.util.HashMap<>();

        @Override public List<CabinAccessToken> loadAll() { return List.copyOf(byToken.values()); }

        @Override public java.util.Optional<CabinAccessToken> findByToken(String token) {
            return java.util.Optional.ofNullable(byToken.get(token));
        }

        @Override public void save(CabinAccessToken token) { byToken.put(token.token(), token); }

        @Override public void revoke(String id, java.time.Instant revokedAt) {
            byToken.replaceAll((t, existing) -> existing.id().equals(id)
                ? new CabinAccessToken(existing.id(), existing.token(), existing.label(), existing.scope(),
                    existing.expiresAt(), revokedAt, existing.createdBy(), existing.createdAt())
                : existing);
        }
    }
}
