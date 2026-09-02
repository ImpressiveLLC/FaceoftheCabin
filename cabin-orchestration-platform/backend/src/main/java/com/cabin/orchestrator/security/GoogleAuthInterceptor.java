package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
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

    private final RestTemplate http = new RestTemplate();
    private final CabinAccessTokenService accessTokens;

    public GoogleAuthInterceptor(CabinAccessTokenService accessTokens) {
        this.accessTokens = accessTokens;
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
            request.setAttribute(REQUEST_ATTR_EMAIL, info.get("email"));
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
    private static final Map<String, String> SCOPE_PATH_PREFIXES = Map.of(
        "dashboard", "/api/dashboard",
        "device_states", "/api/devices",
        "alerts_read", "/api/alerts",
        "observations_read", "/api/events/telemetry-history"
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
            .map(prefix -> contextPath + prefix)
            .anyMatch(base -> path.equals(base) || path.startsWith(base + "/"));
        if (!covered) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "This access link doesn't cover that page");
            return false;
        }
        request.setAttribute(REQUEST_ATTR_GUEST_TOKEN_ID, accessToken.id());
        return true;
    }
}
