package com.cabin.orchestrator.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused on the /api/rules/** carve-out added 2026-08-14 (external review
 * of the first live workflow test: an unauthenticated write endpoint that
 * can arm a real-actuator-firing rule is a materially bigger exposure than
 * a generic device-status GET), plus the Tier 1 guest-share-link path
 * added 2026-09-01. No real Google token is available in a test run, so
 * the Google-path assertions are about which requests reach the token
 * check at all, not about token validity itself -- that half is already
 * implicitly covered by every other gated endpoint using this same class.
 * The guest-token assertions ARE full end-to-end, since that path never
 * calls out to Google at all.
 */
class GoogleAuthInterceptorTest {

    private CabinAccessTokenService accessTokens;
    private GoogleAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        accessTokens = new CabinAccessTokenService(new InMemoryAccessTokenStore());
        interceptor = new GoogleAuthInterceptor(accessTokens);
    }

    /** Minimal in-memory fake -- no database needed for this unit test. */
    private static final class InMemoryAccessTokenStore implements CabinAccessTokenStore {
        private final Map<String, CabinAccessToken> byToken = new HashMap<>();

        @Override public List<CabinAccessToken> loadAll() { return List.copyOf(byToken.values()); }

        @Override public Optional<CabinAccessToken> findByToken(String token) {
            return Optional.ofNullable(byToken.get(token));
        }

        @Override public void save(CabinAccessToken token) { byToken.put(token.token(), token); }

        @Override public void revoke(String id, Instant revokedAt) {
            byToken.replaceAll((t, existing) -> existing.id().equals(id)
                ? new CabinAccessToken(existing.id(), existing.token(), existing.label(), existing.scope(),
                    existing.expiresAt(), revokedAt, existing.createdBy(), existing.createdAt())
                : existing);
        }
    }

    @Test
    void aValidGuestTokenScopedToDeviceStatesReachesApiDevices() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("device_states"), null, "nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.setParameter("t", token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void aGuestTokenNotScopedForThePathIsRejected() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("alerts_read"), null, "nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.setParameter("t", token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void aGuestTokenCanNeverWriteEvenWhenTheRouteIsInItsScope() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("device_states"), null, "nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/devices/z2m-main_water_valve/config");
        request.setParameter("t", token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void anUnknownGuestTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.setParameter("t", "not-a-real-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void aRevokedGuestTokenIsRejected() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("device_states"), null, "nate@example.com");
        accessTokens.revoke(token.id());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.setParameter("t", token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void anExpiredGuestTokenIsRejected() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("device_states"), Duration.ofDays(-1), "nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.setParameter("t", token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void aGuestTokenViaTheCabinTokenAuthorizationHeaderAlsoWorks() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("alerts_read"), null, "nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/alerts/active");
        request.addHeader("Authorization", "CabinToken " + token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void aGuestTokenCanNeverReachAccessTokenAdministrationItself() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("device_states", "alerts_read"), null, "nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/access-tokens");
        request.setParameter("t", token.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(403, response.getStatus());
    }

    @Test
    void getOnRulesWorkflowsPassesThroughWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rules/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertTrue(allowed);
        assertEquals(200, response.getStatus(), "no error should have been written for an allowed request");
    }

    @Test
    void getOnRulesExecutionsPassesThroughWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rules/workflows/wf-1/executions");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void postOnRulesWorkflowsIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rules/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed);
        assertEquals(401, response.getStatus());
    }

    @Test
    void postOnRulesFireIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/rules/workflows/wf-1/fire");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void deleteOnRulesWorkflowsIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("DELETE", "/api/rules/workflows/wf-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void optionsPreflightOnRulesPassesThroughRegardless() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/rules/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void postOnAnUnrelatedOpenPathStillRequiresNoTokenCheckHere() throws Exception {
        // Sanity check that the new carve-out is scoped to /api/rules/** only --
        // this interceptor is never registered against /api/devices/** in
        // WebConfig, so this isn't testing THIS class's behavior on that path
        // so much as confirming the /api/rules/ prefix check isn't accidentally
        // matching something broader like /api/rulesengine/whatever.
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rulesengine/not-actually-rules");
        MockHttpServletResponse response = new MockHttpServletResponse();
        // No token set -- if the prefix check were too broad (e.g. "contains"
        // instead of startsWith(".../api/rules/")) this would incorrectly pass.
        ReflectionTestUtils.setField(interceptor, "expectedClientId", "");

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertFalse(allowed, "/api/rulesengine/... must not be treated as an /api/rules/... read");
    }
}
