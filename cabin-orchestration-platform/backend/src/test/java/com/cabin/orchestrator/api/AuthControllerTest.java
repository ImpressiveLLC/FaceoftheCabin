package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.CabinSession;
import com.cabin.orchestrator.security.CabinSessionService;
import com.cabin.orchestrator.security.CabinSessionStore;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AuthController itself does no auth checking -- it trusts REQUEST_ATTR_EMAIL,
 * which by the time this controller runs has already been set by
 * GoogleAuthInterceptor (WebConfig gates /api/auth/session through it). These
 * tests exercise that contract directly, the same way RulesControllerTest
 * exercises actorEmail() by setting the attribute on a MockHttpServletRequest.
 */
class AuthControllerTest {

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

    private AuthController newController(CabinSessionService sessions) {
        return new AuthController(sessions);
    }

    @Test
    @SuppressWarnings("unchecked")
    void issuesASessionTokenWhenTheInterceptorAlreadyVouchedForACaller() {
        CabinSessionService sessions = new CabinSessionService(new InMemoryCabinSessionStore());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/session");
        request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL, "nate@example.com");

        ResponseEntity<?> result = newController(sessions).issueSession(request);

        assertEquals(HttpStatus.OK, result.getStatusCode());
        Map<String, Object> body = (Map<String, Object>) result.getBody();
        assertNotNull(body.get("token"));
        assertNotNull(body.get("expiresAt"));
    }

    @Test
    void refusesToIssueWithoutAnAuthenticatedEmailAttribute() {
        CabinSessionService sessions = new CabinSessionService(new InMemoryCabinSessionStore());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/session");

        ResponseEntity<?> result = newController(sessions).issueSession(request);

        assertEquals(HttpStatus.UNAUTHORIZED, result.getStatusCode());
    }

    @Test
    void revokingATokenMakesItStopWorkingImmediately() {
        CabinSessionService sessions = new CabinSessionService(new InMemoryCabinSessionStore());
        CabinSession session = sessions.issue("nate@example.com");
        AuthController controller = newController(sessions);

        controller.revokeSession(Map.of("token", session.token()));

        assertTrue(sessions.validateAndExtend(session.token()).isEmpty());
    }

    @Test
    void revokingWithNoTokenInTheBodyIsAHarmlessNoOp() {
        CabinSessionService sessions = new CabinSessionService(new InMemoryCabinSessionStore());

        ResponseEntity<?> result = newController(sessions).revokeSession(Map.of());

        assertEquals(HttpStatus.OK, result.getStatusCode());
    }
}
