package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.CabinSession;
import com.cabin.orchestrator.security.CabinSessionService;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 2026-09-04 -- the exchange endpoint CabinSession exists to make possible:
 * a caller who just proved a real identity (any of the three ways
 * GoogleAuthInterceptor already recognizes -- a fresh Google token, an
 * existing ManagedSession, or an existing CabinSession up for renewal)
 * trades that proof for a 30-day rolling session, so the frontend never
 * has to re-run Google's interactive sign-in popup just because its
 * original ~1-hour access token expired.
 *
 * This endpoint is itself registered behind GoogleAuthInterceptor in
 * WebConfig -- by the time this method runs, REQUEST_ATTR_EMAIL is already
 * set by whichever of the three paths the caller actually used. That's the
 * whole trick: this controller doesn't re-implement any auth check, it just
 * asks "did the interceptor already vouch for someone" and issues a
 * CabinSession for that email if so.
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin
public class AuthController {

    private final CabinSessionService cabinSessions;

    public AuthController(CabinSessionService cabinSessions) {
        this.cabinSessions = cabinSessions;
    }

    /** Issues a fresh 30-day CabinSession token for whoever the interceptor already authenticated this request as. */
    @PostMapping("/session")
    public ResponseEntity<?> issueSession(HttpServletRequest request) {
        Object email = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        if (email == null) {
            // Shouldn't be reachable in practice -- WebConfig gates this path,
            // so the interceptor already rejected anything that got here
            // without setting this attribute. Defensive, not load-bearing.
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Not authenticated"));
        }
        CabinSession session = cabinSessions.issue(email.toString());
        return ResponseEntity.ok(Map.of(
            "token", session.token(),
            "expiresAt", session.expiresAt().toString()));
    }

    /** Explicit sign-out for a CabinSession specifically -- a Google/ManagedSession sign-out is handled client-side (each has its own revoke path already). */
    @PostMapping("/session/revoke")
    public ResponseEntity<?> revokeSession(@RequestBody Map<String, String> body) {
        String token = body.get("token");
        if (token != null && !token.isBlank()) {
            cabinSessions.revoke(token);
        }
        return ResponseEntity.ok(Map.of("status", "revoked"));
    }
}
