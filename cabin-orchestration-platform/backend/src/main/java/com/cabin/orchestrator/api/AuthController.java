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
 * a caller who just proved a real Google identity (a fresh Google token, or
 * an existing CabinSession up for renewal) trades that proof for a fresh
 * 30-day rolling session, so the frontend never has to re-run Google's
 * interactive sign-in popup just because its original ~1-hour access token
 * expired.
 *
 * This endpoint is itself registered behind GoogleAuthInterceptor in
 * WebConfig -- by the time this method runs, REQUEST_ATTR_EMAIL is already
 * set by whichever path the caller actually used. That's most of the
 * trick: this controller doesn't re-implement any auth check, it just asks
 * "did the interceptor already vouch for someone" and issues a CabinSession
 * for that email if so.
 *
 * Deliberately NOT extended to ManagedSession callers (see the
 * REQUEST_ATTR_MANAGED_USER_ID guard below) -- found 2026-09-05 while
 * wiring the first real ManagedSession-issuing client (the magic-link
 * landing page): GoogleAuthInterceptor.handleManagedSession() already lets
 * a ManagedSession token reach this path (it isn't under /api/managed-users
 * or /api/access-tokens), and this method used to have nothing stopping it
 * from minting a CabinSession for that email. That would have been a real
 * privilege-escalation + revocation-bypass hole: CabinSessionService.
 * validateAndExtend() only checks its own store (no idea a managed user
 * exists behind the email), and resolveHouseholdRole() has no concept of
 * ManagedUserRole -- every CabinSession gets full ADULT_HOUSEHOLD_MEMBER
 * trust. A VIEWER-role managed user could have exchanged their read-only
 * session for full write access, and it would have kept working for up to
 * 30 sliding days even after an admin deactivated them (ManagedUserService.
 * validateSession()'s own immediate-revoke-on-deactivate guarantee only
 * applies to the ManagedSession path itself). This was reachable but dormant
 * since 2026-09-04 -- no client spoke ManagedSession tokens until now.
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
        if (request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_MANAGED_USER_ID) != null) {
            // See this class's own javadoc for the full reasoning -- a
            // ManagedSession is already its own bounded, revocable
            // credential; upgrading it to a CabinSession would silently
            // drop both its VIEWER read-only enforcement and its
            // immediate-revoke-on-deactivate guarantee.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "Managed-user sessions can't be upgraded to a standing CabinSession"));
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
