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
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing bearer token");
            return false;
        }
        String token = header.substring(7);
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
}
