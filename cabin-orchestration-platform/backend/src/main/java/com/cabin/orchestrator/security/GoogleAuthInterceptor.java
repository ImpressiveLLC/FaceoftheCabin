package com.cabin.orchestrator.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

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

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
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
            return true;
        } catch (RestClientException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Token validation failed");
            return false;
        }
    }

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
}
