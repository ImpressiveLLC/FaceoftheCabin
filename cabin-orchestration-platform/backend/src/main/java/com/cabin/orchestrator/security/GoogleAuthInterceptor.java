package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * cabin-backend (api.unicornpingpong.com) is public now, same as family-hub
 * — see docs/EXECUTION_PLAN_2026-07-30.md / session notes on the "public
 * core app, Tailscale-only admin surfaces" decision. Notes and chore-
 * completion are the first *write* endpoints exposed to the open internet
 * on this backend, so unlike the existing device-status endpoints they
 * need a real gate: anyone with a valid Google access token can write
 * (same "same trust as a fridge note" model already used client-side in
 * family-hub.html's actor picker — this isn't about WHO, only about
 * blocking anonymous internet traffic from hitting the API directly).
 *
 * Validates the bearer token against Google's tokeninfo endpoint on every
 * request rather than caching — traffic here is a handful of family
 * members, not worth the complexity of a token cache yet.
 */
@Component
public class GoogleAuthInterceptor implements HandlerInterceptor {

    @Value("${cabin.google.oauthClientId:}")
    private String expectedClientId;

    // D11's authorization-model hard gate (WSJF #6) -- comma-separated
    // Google account emails treated as ADMINISTRATOR; every other
    // authenticated principal (Google or managed-session) is
    // ADULT_HOUSEHOLD_MEMBER, matching this app's existing "any signed-in
    // account is a trusted adult" behavior exactly, just now given a name
    // a per-route check can reason about. Same source ADMIN_EMAILS already
    // feeds the frontend -- see application.yml's cabin.admin.emails.
    @Value("${cabin.admin.emails:}")
    private String adminEmailsRaw;

    private final RestTemplate http = new RestTemplate();
    private final CabinAccessTokenService accessTokens;
    private final ManagedUserService managedUsers;
    private final CabinSessionService cabinSessions;

    public GoogleAuthInterceptor(CabinAccessTokenService accessTokens, ManagedUserService managedUsers,
                                  CabinSessionService cabinSessions) {
        this.accessTokens = accessTokens;
        this.managedUsers = managedUsers;
        this.cabinSessions = cabinSessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // CORS preflight (OPTIONS) is a browser-internal permissions check --
        // it never carries a real credential, and Firefox/Chrome both
        // require the preflight response itself to be a plain 2xx or they
        // refuse to send the real request at all, regardless of which CORS
        // headers are present on a non-2xx response. This interceptor was
        // rejecting every preflight with 401 (no token on an OPTIONS
        // request, correctly -- there never is one), which silently broke
        // every authenticated cross-origin call (notes/chores/profiles/
        // camera) from any real browser -- found 2026-08-03 via a real
        // user's Firefox network trace after curl-based testing repeatedly
        // (and wrongly) looked fine, since curl doesn't enforce this rule.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // The exact /api/tech-id/findings collection endpoint carries its
        // own, method-specific gating (TechIdController): POST there checks
        // a shared-secret API key since submitters are automated providers,
        // not signed-in humans; GET is intentionally open, matching
        // /api/events. This is an EXACT match, not a prefix -- sub-paths
        // like /api/tech-id/findings/{id} (PATCH: human adjudication) and
        // /api/tech-id/findings/{id}/actions (POST: human action-logging,
        // added for the Opportunity Map's See/Think/Act log) must still go
        // through the Google-token check below. Spring's addPathPatterns is
        // method-agnostic, so this split has to happen here instead of in
        // WebConfig.
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        boolean isFindingsCollection = path.equals(contextPath + "/api/tech-id/findings");
        if (isFindingsCollection
                && ("GET".equalsIgnoreCase(request.getMethod()) || "POST".equalsIgnoreCase(request.getMethod()))) {
            return true;
        }
        // /api/rules/** reads (listing workflows, execution history) stay
        // open, matching every other read-only resource in this app —
        // only writes (create/activate/deactivate/delete/fire/view/clear)
        // need a verified identity, since GET here can't fire a physical
        // action. Method-only (not exact-path like the findings carve-out
        // above) because every GET under this prefix is legitimately open
        // and every non-GET is legitimately gated — no path-specific split
        // needed the way tech-id's dual-auth-model endpoint required.
        boolean isRulesRead = path.startsWith(contextPath + "/api/rules/") && "GET".equalsIgnoreCase(request.getMethod());
        if (isRulesRead) {
            return true;
        }
        // /api/kb/** reads (GET .../nodes, .../nodes/{entityRef}) stay open,
        // same reasoning as /api/rules/** above. Found 2026-09-02 while
        // correcting a stale KnowledgeNode: .../regenerate and .../curate
        // are real writes (curate especially -- the one path that persists
        // MANUALLY_CURATED troubleshooting content) that had no gate at all
        // before this, in the same "unauthenticated write reachable from
        // the open internet" category as the 2026-09-01 events/alerts fix.
        boolean isKbRead = path.startsWith(contextPath + "/api/kb/") && "GET".equalsIgnoreCase(request.getMethod());
        if (isKbRead) {
            return true;
        }
        // /api/events/telemetry-history and /api/events/reported-fields stay
        // open -- found 2026-09-04, same day /api/devices and /api/alerts
        // were ungated: these two are numeric sensor-history endpoints
        // (humidity/temperature/CO readings over time), not the camera/
        // motion event log the /api/events collection gate exists to
        // protect. They got swept up in that gate purely by sharing the
        // /api/events/** prefix, which silently broke SensorHistoryPanel's
        // charting/dropdowns/history entirely for a not-signed-in caller --
        // the exact same "reveals whether anyone's home" test the events
        // gate is actually for doesn't apply to a humidity reading. The
        // bare GET /api/events collection (camera/motion replay) stays
        // gated below; only these two exact sub-paths are exempted.
        boolean isSensorHistoryRead = "GET".equalsIgnoreCase(request.getMethod())
            && (path.equals(contextPath + "/api/events/telemetry-history")
                || path.equals(contextPath + "/api/events/reported-fields"));
        if (isSensorHistoryRead) {
            return true;
        }
        // Tier 2 managed-user magic-link consumption -- a managed user has
        // no Google account by definition, so the one endpoint that
        // exchanges their clicked email link for a real session can never
        // itself require one. Same exact-prefix carve-out technique as the
        // tech-id findings collection above. Every OTHER /api/managed-users
        // endpoint (list/create/deactivate/invite) stays behind the normal
        // Google gate via WebConfig's path pattern.
        boolean isMagicLinkConsume = path.startsWith(contextPath + "/api/managed-users/magic/")
            && "POST".equalsIgnoreCase(request.getMethod());
        if (isMagicLinkConsume) {
            return true;
        }
        // Tier 2 managed-user sessions -- see ManagedUserService's own doc.
        // Checked before the Tier 1 guest-token check below: unlike a guest
        // link (anonymous, scope-limited to 4 fixed read paths), a managed
        // user gets the same broad read access a Google account gets, with
        // writes allowed only for HOUSEHOLD_MEMBER (VIEWER stays read-only,
        // same blanket rule guest tokens use). Sets REQUEST_ATTR_EMAIL to
        // the managed user's own email -- the SAME attribute Google auth
        // sets -- so every downstream consumer (createdBy, actor
        // attribution) sees one consistent principal shape regardless of
        // which of the three auth paths actually authenticated the request.
        String managedSessionToken = extractManagedSessionToken(request);
        if (managedSessionToken != null) {
            return handleManagedSession(managedSessionToken, request, response);
        }
        // 2026-09-04 -- a real Google-authenticated caller's 30-day rolling
        // recognition (CabinSessionService), issued by POST /api/auth/session
        // in exchange for a fresh Google token. Checked before the raw
        // Google-token validation below so a caller holding one of these
        // never needs to hit Google's tokeninfo endpoint (or re-run the
        // interactive sign-in popup) just because their original ~1-hour
        // Google access token expired -- see CabinSession's own doc for why
        // that friction is the whole problem this exists to remove. Full
        // access, same as a live Google token (not VIEWER-limited like a
        // managed session): this represents someone who already proved a
        // real Google identity, not a passwordless invite.
        String cabinSessionToken = extractCabinSessionToken(request);
        if (cabinSessionToken != null) {
            return handleCabinSession(cabinSessionToken, request, response);
        }
        // Tier 1 guest share links -- see CabinAccessTokenService's own doc
        // and the plan's "Guest Access Model" section. Checked before the
        // Google-token requirement below so a share link is a genuine
        // alternative credential (e.g. for an insurance adjuster with no
        // Google account), not something that falls through to "missing
        // bearer token." A guest token is always read-only and scope-
        // limited regardless of what a caller asks for -- see
        // handleGuestToken()/SCOPE_PATH_PREFIXES.
        String guestToken = extractGuestToken(request);
        if (guestToken != null) {
            return handleGuestToken(guestToken, request, response);
        }
        String token = extractToken(request);
        if (token == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing bearer token");
            return false;
        }
        try {
            Map<?, ?> info = http.getForObject(
                "https://oauth2.googleapis.com/tokeninfo?access_token={token}", Map.class, token);
            if (info == null || info.get("error") != null) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired token");
                return false;
            }
            if (expectedClientId != null && !expectedClientId.isBlank()
                && !expectedClientId.equals(info.get("aud"))) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token was not issued for this app");
                return false;
            }
            // Stashed for controllers that need "who did this" (e.g.
            // TechIdController's action log) without a second network
            // round-trip to Google -- see REQUEST_ATTR_EMAIL's own javadoc.
            String email = (String) info.get("email");
            request.setAttribute(REQUEST_ATTR_EMAIL, email);
            request.setAttribute(REQUEST_ATTR_HOUSEHOLD_ROLE, resolveHouseholdRole(email));
            return true;
        } catch (RestClientException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token validation failed");
            return false;
        }
    }

    /** Request attribute key holding the token's verified Google account email, set only after a successful check above. */
    public static final String REQUEST_ATTR_EMAIL = "cabin.auth.googleEmail";

    // Header is the primary path (used by every existing fetch()-based
    // caller). Query param exists only for /api/camera/{camera}/live's
    // <img> tag, which can't set a custom Authorization header -- MJPEG
    // multipart streams can't be blob-fetched the way a snapshot/clip can
    // (see CameraMediaController's javadoc). Same token, same validation
    // either way, just a different transport for the one case that needs it.
    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ") && header.length() > 7) {
            return header.substring(7);
        }
        String queryToken = request.getParameter("access_token");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
        return null;
    }

    /** Request attribute key holding the guest link's id, set only after a successful check below -- lets a handler tell a share-link caller apart from a signed-in one if it ever needs to. */
    public static final String REQUEST_ATTR_GUEST_TOKEN_ID = "cabin.auth.guestTokenId";

    // Only the four routes D12 (Guest Access & Non-Google Auth) names as
    // safe for an unauthenticated-by-Google party: current-conditions
    // dashboard, device state summary, recent alerts log, historical
    // sensor readings. Nothing under /api/admin, /api/rules (writes),
    // /api/access-tokens itself, or any other write path -- a scope
    // string with no entry here simply grants nothing.
    // No trailing slash -- matched below against both the bare collection
    // endpoint (e.g. "/api/devices" itself) and any sub-path
    // ("/api/devices/{id}/..."). A naive "/api/devices/" prefix excludes
    // the collection endpoint itself, since it has no trailing slash.
    //
    // observations_read maps to TWO prefixes, not one -- found fixing bug
    // #1 (GuestDashboard showed "invalid link" for any scope other than
    // device_states+alerts_read together): /api/events/telemetry-history
    // alone needs a deviceId+field the caller must already know, and the
    // only place to discover those without device_states granted too is
    // /api/events/reported-fields (device->field-names only, no raw event
    // content -- deliberately NOT the broader /api/events collection,
    // which carries camera/motion event replay and stays out of scope).
    private static final Map<String, List<String>> SCOPE_PATH_PREFIXES = Map.of(
        "dashboard", List.of("/api/dashboard"),
        "device_states", List.of("/api/devices"),
        "alerts_read", List.of("/api/alerts"),
        "observations_read", List.of("/api/events/telemetry-history", "/api/events/reported-fields")
    );

    private String extractGuestToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("CabinToken ") && header.length() > 11) {
            return header.substring(11);
        }
        String param = request.getParameter("t");
        return (param == null || param.isBlank()) ? null : param;
    }

    private boolean handleGuestToken(String rawToken, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var tokenOpt = accessTokens.validate(rawToken);
        if (tokenOpt.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "This access link is invalid, expired, or has been revoked");
            return false;
        }
        // A guest link is never a write credential, full stop -- checked
        // before scope so a POST/PATCH/DELETE can't slip through even if
        // its path happens to also match a granted read prefix.
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This access link is read-only");
            return false;
        }
        CabinAccessToken accessToken = tokenOpt.get();
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        boolean covered = accessToken.scope().stream()
            .map(SCOPE_PATH_PREFIXES::get)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .map(prefix -> contextPath + prefix)
            .anyMatch(base -> path.equals(base) || path.startsWith(base + "/"));
        if (!covered) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This access link doesn't cover that page");
            return false;
        }
        request.setAttribute(REQUEST_ATTR_GUEST_TOKEN_ID, accessToken.id());
        return true;
    }

    /** Request attribute key holding the managed user's own id, set only after a successful check below. */
    public static final String REQUEST_ATTR_MANAGED_USER_ID = "cabin.auth.managedUserId";

    private String extractManagedSessionToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("ManagedSession ") && header.length() > 15) {
            return header.substring(15);
        }
        return null;
    }

    private boolean handleManagedSession(String rawToken, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var userOpt = managedUsers.validateSession(rawToken);
        if (userOpt.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "This session is invalid, expired, revoked, or the account is no longer active");
            return false;
        }
        // Account/access management stays strictly admin-only regardless of
        // role -- a HOUSEHOLD_MEMBER gets "same trust as a fridge note" for
        // OPERATING the cabin (chores, notes, workflows, camera), not for
        // managing who else has access. Checked before the write/GET gate
        // below so a VIEWER can't reach these as a GET either (e.g. listing
        // every other managed user's email).
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (path.startsWith(contextPath + "/api/managed-users") || path.startsWith(contextPath + "/api/access-tokens")) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Managing access/accounts requires a real admin sign-in");
            return false;
        }
        ManagedUser user = userOpt.get();
        if (!user.role().allowsWrite() && !"GET".equalsIgnoreCase(request.getMethod())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This account is read-only");
            return false;
        }
        request.setAttribute(REQUEST_ATTR_EMAIL, user.email());
        request.setAttribute(REQUEST_ATTR_MANAGED_USER_ID, user.id());
        request.setAttribute(REQUEST_ATTR_HOUSEHOLD_ROLE, resolveHouseholdRole(user.email()));
        return true;
    }

    // Query param exists for the same reason extractToken()'s does: an
    // <img src="...">-based live camera stream can't set a custom
    // Authorization header. Found 2026-09-04 (direct user report, live
    // regression): the camera live view was still built from the raw
    // ~1-hour Google access token specifically, so once that expired while
    // a CabinSession kept the rest of the app working fine, the stream
    // broke (a stale/empty access_token query param renders as a blank/
    // green frame) even though nothing else looked signed-out.
    private String extractCabinSessionToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("CabinSession ") && header.length() > 13) {
            return header.substring(13);
        }
        String queryToken = request.getParameter("cabin_session");
        if (queryToken != null && !queryToken.isBlank()) {
            return queryToken;
        }
        return null;
    }

    private boolean handleCabinSession(String rawToken, HttpServletRequest request, HttpServletResponse response) throws IOException {
        var emailOpt = cabinSessions.validateAndExtend(rawToken);
        if (emailOpt.isEmpty()) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "This session is invalid, expired, or revoked -- sign in again");
            return false;
        }
        String email = emailOpt.get();
        request.setAttribute(REQUEST_ATTR_EMAIL, email);
        request.setAttribute(REQUEST_ATTR_HOUSEHOLD_ROLE, resolveHouseholdRole(email));
        return true;
    }

    /** Request attribute key holding the server-derived HouseholdRole -- see that enum's own doc. Not set for Tier 1 guest tokens (a genuinely different, orthogonal concept: scoped external access, not a household role). */
    public static final String REQUEST_ATTR_HOUSEHOLD_ROLE = "cabin.auth.householdRole";

    private HouseholdRole resolveHouseholdRole(String email) {
        return isAdminEmail(email) ? HouseholdRole.ADMINISTRATOR : HouseholdRole.ADULT_HOUSEHOLD_MEMBER;
    }

    private boolean isAdminEmail(String email) {
        if (email == null || adminEmailsRaw == null || adminEmailsRaw.isBlank()) return false;
        for (String candidate : adminEmailsRaw.split(",")) {
            if (candidate.trim().equalsIgnoreCase(email.trim())) return true;
        }
        return false;
    }
}
