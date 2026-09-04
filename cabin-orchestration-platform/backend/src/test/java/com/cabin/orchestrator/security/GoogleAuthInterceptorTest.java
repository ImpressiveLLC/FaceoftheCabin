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
    private ManagedUserService managedUsers;
    private CabinSessionService cabinSessions;
    private GoogleAuthInterceptor interceptor;
    private final List<String> sentMagicLinkUrls = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        accessTokens = new CabinAccessTokenService(new InMemoryAccessTokenStore());
        sentMagicLinkUrls.clear();
        managedUsers = new ManagedUserService(new InMemoryManagedUserStore(), new InMemoryMagicLinkTokenStore(),
            new InMemoryManagedUserSessionStore(), (to, name, url) -> sentMagicLinkUrls.add(url));
        // @Value's default expression is only resolved by Spring's own
        // injection -- outside a container (this test constructs the
        // service directly), the field stays null unless set explicitly,
        // same convention already used for expectedClientId below.
        ReflectionTestUtils.setField(managedUsers, "frontendOrigin", "http://localhost:5173");
        cabinSessions = new CabinSessionService(new InMemoryCabinSessionStore());
        interceptor = new GoogleAuthInterceptor(accessTokens, managedUsers, cabinSessions);
    }

    /** Real end-to-end create -> invite -> click -> consume, same path a real managed user goes through, returning the resulting session's bearer token. */
    private String issueManagedSessionToken(ManagedUserRole role) {
        ManagedUser user = managedUsers.create("member@example.com", "Household Member", role, "nate@example.com");
        managedUsers.invite(user.id());
        String url = sentMagicLinkUrls.get(sentMagicLinkUrls.size() - 1);
        String magicToken = url.substring(url.lastIndexOf('/') + 1);
        return managedUsers.consumeMagicLink(magicToken).orElseThrow().token();
    }

    /** Minimal in-memory fakes -- no database needed for this unit test, mirroring InMemoryAccessTokenStore above. */
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

    private static final class InMemoryCabinSessionStore implements CabinSessionStore {
        private final Map<String, CabinSession> byToken = new HashMap<>();
        @Override public void save(CabinSession session) { byToken.put(session.token(), session); }
        @Override public Optional<CabinSession> findByToken(String token) { return Optional.ofNullable(byToken.get(token)); }
        @Override public void extend(String token, Instant newExpiresAt) {
            byToken.computeIfPresent(token, (t, existing) -> new CabinSession(
                existing.token(), existing.googleEmail(), newExpiresAt, existing.revokedAt(), existing.createdAt()));
        }
        @Override public void revoke(String token, Instant revokedAt) {
            byToken.computeIfPresent(token, (t, existing) -> new CabinSession(
                existing.token(), existing.googleEmail(), existing.expiresAt(), revokedAt, existing.createdAt()));
        }
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

    // D12's fourth scope, added 2026-09-01 once the target endpoint was confirmed.
    @Test
    void aGuestTokenScopedToObservationsReadReachesTelemetryHistoryButNotOtherEventRoutes() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("observations_read"), null, "nate@example.com");
        MockHttpServletRequest allowed = new MockHttpServletRequest("GET", "/api/events/telemetry-history");
        allowed.setParameter("t", token.token());
        assertTrue(interceptor.preHandle(allowed, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest otherEvents = new MockHttpServletRequest("GET", "/api/events");
        otherEvents.setParameter("t", token.token());
        MockHttpServletResponse otherEventsResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(otherEvents, otherEventsResponse, new Object()),
            "observations_read must not grant the broader /api/events, only telemetry-history and reported-fields");
        assertEquals(403, otherEventsResponse.getStatus());
    }

    // Bug #1 fix: observations_read alone (no device_states) has no way to
    // discover which deviceId/field combos exist without this -- see
    // SCOPE_PATH_PREFIXES's own comment. Deliberately narrow (device->field
    // names only), not the broader /api/events the test above still rejects.
    @Test
    void aGuestTokenScopedToObservationsReadAlsoReachesReportedFields() throws Exception {
        CabinAccessToken token = accessTokens.create("Insurance Claim", List.of("observations_read"), null, "nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/events/reported-fields");
        request.setParameter("t", token.token());

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
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

    // ── Tier 2 managed users (Sprint 4, added 2026-09-02) ──

    @Test
    void aValidHouseholdMemberSessionCanReadAndWrite() throws Exception {
        String sessionToken = issueManagedSessionToken(ManagedUserRole.HOUSEHOLD_MEMBER);
        MockHttpServletRequest getReq = new MockHttpServletRequest("GET", "/api/devices");
        getReq.addHeader("Authorization", "ManagedSession " + sessionToken);
        assertTrue(interceptor.preHandle(getReq, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest patchReq = new MockHttpServletRequest("PATCH", "/api/notes/1");
        patchReq.addHeader("Authorization", "ManagedSession " + sessionToken);
        assertTrue(interceptor.preHandle(patchReq, new MockHttpServletResponse(), new Object()),
            "HOUSEHOLD_MEMBER has the same write trust as a signed-in Google account");
    }

    @Test
    void aValidViewerSessionCanReadButNotWrite() throws Exception {
        String sessionToken = issueManagedSessionToken(ManagedUserRole.VIEWER);
        MockHttpServletRequest getReq = new MockHttpServletRequest("GET", "/api/devices");
        getReq.addHeader("Authorization", "ManagedSession " + sessionToken);
        assertTrue(interceptor.preHandle(getReq, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest patchReq = new MockHttpServletRequest("PATCH", "/api/notes/1");
        patchReq.addHeader("Authorization", "ManagedSession " + sessionToken);
        MockHttpServletResponse patchResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(patchReq, patchResponse, new Object()));
        assertEquals(403, patchResponse.getStatus());
    }

    @Test
    void aValidManagedSessionSetsTheSameEmailAttributeAGoogleTokenWould() throws Exception {
        String sessionToken = issueManagedSessionToken(ManagedUserRole.HOUSEHOLD_MEMBER);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "ManagedSession " + sessionToken);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals("member@example.com", request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL),
            "downstream createdBy/actor-attribution code must see one consistent principal shape regardless of auth path");
    }

    @Test
    void aManagedSessionEmailMatchingAdminEmailsResolvesToAdministratorRole() throws Exception {
        // D11 authorization-model hard gate (WSJF #6) -- role derivation must
        // work identically regardless of which auth path produced the email,
        // matching aValidManagedSessionSetsTheSameEmailAttributeAGoogleTokenWould
        // above proving the email attribute itself is path-independent.
        ReflectionTestUtils.setField(interceptor, "adminEmailsRaw", "member@example.com, someone-else@example.com");
        String sessionToken = issueManagedSessionToken(ManagedUserRole.HOUSEHOLD_MEMBER);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "ManagedSession " + sessionToken);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(HouseholdRole.ADMINISTRATOR, request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE));
    }

    @Test
    void aManagedSessionEmailNotInAdminEmailsResolvesToAdultHouseholdMemberRole() throws Exception {
        ReflectionTestUtils.setField(interceptor, "adminEmailsRaw", "someone-else@example.com");
        String sessionToken = issueManagedSessionToken(ManagedUserRole.HOUSEHOLD_MEMBER);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "ManagedSession " + sessionToken);

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        assertEquals(HouseholdRole.ADULT_HOUSEHOLD_MEMBER, request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE));
    }

    @Test
    void anUnknownManagedSessionTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "ManagedSession not-a-real-session");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void aRevokedManagedSessionIsRejected() throws Exception {
        String sessionToken = issueManagedSessionToken(ManagedUserRole.HOUSEHOLD_MEMBER);
        managedUsers.revokeSession(sessionToken);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "ManagedSession " + sessionToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void aDeactivatedManagedUsersExistingSessionStopsWorkingImmediately() throws Exception {
        ManagedUser user = managedUsers.create("member@example.com", "Household Member", ManagedUserRole.HOUSEHOLD_MEMBER, "nate@example.com");
        managedUsers.invite(user.id());
        String url = sentMagicLinkUrls.get(0);
        String sessionToken = managedUsers.consumeMagicLink(url.substring(url.lastIndexOf('/') + 1)).orElseThrow().token();
        managedUsers.setActive(user.id(), false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "ManagedSession " + sessionToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()),
            "deactivating a managed user must invalidate their existing session immediately, not just block future invites");
    }

    @Test
    void aManagedSessionCanNeverReachManagedUserOrAccessTokenAdministration() throws Exception {
        String sessionToken = issueManagedSessionToken(ManagedUserRole.HOUSEHOLD_MEMBER);

        MockHttpServletRequest managedUsersReq = new MockHttpServletRequest("GET", "/api/managed-users");
        managedUsersReq.addHeader("Authorization", "ManagedSession " + sessionToken);
        MockHttpServletResponse managedUsersResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(managedUsersReq, managedUsersResponse, new Object()));
        assertEquals(403, managedUsersResponse.getStatus());

        MockHttpServletRequest accessTokensReq = new MockHttpServletRequest("GET", "/api/access-tokens");
        accessTokensReq.addHeader("Authorization", "ManagedSession " + sessionToken);
        MockHttpServletResponse accessTokensResponse = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(accessTokensReq, accessTokensResponse, new Object()));
        assertEquals(403, accessTokensResponse.getStatus());
    }

    @Test
    void magicLinkConsumptionIsExemptFromTheGoogleTokenRequirement() throws Exception {
        // The one endpoint a managed user (no Google account) must reach
        // with no credential at all -- see GoogleAuthInterceptor's own
        // carve-out comment.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/managed-users/magic/some-token/consume");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void everyOtherManagedUsersEndpointStillRequiresARealGoogleToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/managed-users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    // ── CabinSession: 30-day rolling recognition for a real Google
    // identity (added 2026-09-04) -- see CabinSession's own doc for why. ──

    @Test
    void aValidCabinSessionGrantsFullReadAndWriteAccess() throws Exception {
        CabinSession session = cabinSessions.issue("nate@example.com");
        MockHttpServletRequest getReq = new MockHttpServletRequest("GET", "/api/devices");
        getReq.addHeader("Authorization", "CabinSession " + session.token());
        assertTrue(interceptor.preHandle(getReq, new MockHttpServletResponse(), new Object()));

        MockHttpServletRequest postReq = new MockHttpServletRequest("POST", "/api/notes");
        postReq.addHeader("Authorization", "CabinSession " + session.token());
        assertTrue(interceptor.preHandle(postReq, new MockHttpServletResponse(), new Object()),
            "unlike a managed VIEWER session, a real Google identity's CabinSession is never read-only");
    }

    @Test
    void aValidCabinSessionSetsTheSameEmailAttributeAGoogleTokenWould() throws Exception {
        CabinSession session = cabinSessions.issue("nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "CabinSession " + session.token());

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals("nate@example.com", request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL));
    }

    @Test
    void aCabinSessionForAnAdminEmailResolvesToAdministratorRole() throws Exception {
        ReflectionTestUtils.setField(interceptor, "adminEmailsRaw", "nate@example.com");
        CabinSession session = cabinSessions.issue("nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "CabinSession " + session.token());

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertEquals(HouseholdRole.ADMINISTRATOR, request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE));
    }

    @Test
    void anUnknownCabinSessionTokenIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "CabinSession not-a-real-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void aRevokedCabinSessionIsRejected() throws Exception {
        CabinSession session = cabinSessions.issue("nate@example.com");
        cabinSessions.revoke(session.token());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "CabinSession " + session.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void anExpiredCabinSessionIsRejected() throws Exception {
        CabinSessionStore rawStore = new InMemoryCabinSessionStore();
        CabinSessionService expiredSessions = new CabinSessionService(rawStore);
        CabinSession fresh = expiredSessions.issue("nate@example.com");
        rawStore.extend(fresh.token(), Instant.now().minus(Duration.ofDays(1))); // force it into the past
        GoogleAuthInterceptor expiredInterceptor = new GoogleAuthInterceptor(accessTokens, managedUsers, expiredSessions);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "CabinSession " + fresh.token());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(expiredInterceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void aValidCabinSessionViaTheQueryParamAlsoWorksForImgSrcBasedCameraStreams() throws Exception {
        CabinSession session = cabinSessions.issue("nate@example.com");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/camera/driveway/live");
        request.setParameter("cabin_session", session.token());

        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), new Object()));
    }

    @Test
    void aSuccessfulCabinSessionValidationSlidesTheThirtyDayWindowForward() throws Exception {
        CabinSessionStore rawStore = new InMemoryCabinSessionStore();
        CabinSessionService slidingSessions = new CabinSessionService(rawStore);
        CabinSession original = slidingSessions.issue("nate@example.com");
        // Simulate this session being close to expiry already.
        Instant almostExpired = Instant.now().plus(Duration.ofDays(1));
        rawStore.extend(original.token(), almostExpired);
        GoogleAuthInterceptor slidingInterceptor = new GoogleAuthInterceptor(accessTokens, managedUsers, slidingSessions);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/devices");
        request.addHeader("Authorization", "CabinSession " + original.token());

        assertTrue(slidingInterceptor.preHandle(request, new MockHttpServletResponse(), new Object()));

        CabinSession afterUse = rawStore.findByToken(original.token()).orElseThrow();
        assertTrue(afterUse.expiresAt().isAfter(almostExpired.plus(Duration.ofDays(20))),
            "a successful validate must push expiresAt back out to a fresh ~30 days, not leave the old near-expiry in place");
    }

    // ── /api/kb GET-open, writes-gated (found 2026-09-02) ──

    @Test
    void getOnKbNodesPassesThroughWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kb/nodes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void getOnKbNodesForOneEntityPassesThroughWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kb/nodes/z2m-main_water_valve");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
    }

    @Test
    void postOnKbCurateIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/kb/curate");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }

    @Test
    void postOnKbRegenerateIsRejectedWithoutAToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/kb/regenerate");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
        assertEquals(401, response.getStatus());
    }
}
