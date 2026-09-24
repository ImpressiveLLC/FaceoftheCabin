package com.cabin.orchestrator.security.demo;

import com.cabin.orchestrator.security.CabinAccessToken;
import com.cabin.orchestrator.security.CabinAccessTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * D22 Demo Access gate (R-DM-2, R-DM-4, R-DM-8, R-DM-9, R-DM-10).
 *
 * Runs on every /api/** request ahead of Spring MVC, so it also covers the
 * routes D14 leaves open to anonymous callers (/api/devices, /api/alerts,
 * GET /api/rules/**) -- GoogleAuthInterceptor is only registered on the
 * gated prefixes, so it could not enforce a deny-by-default table on its own.
 *
 * Acts only when the request carries {@code Authorization: CabinToken {t}}
 * and that token holds the {@code demo} scope. Every other caller passes
 * straight through, unchanged. A filter rather than an interceptor so it can
 * clamp telemetry-history's {@code days} parameter (HandlerInterceptor
 * cannot replace the request).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class DemoAccessFilter extends OncePerRequestFilter {

    public static final String DEMO_SCOPE = "demo";
    public static final String REQUEST_ATTR_DEMO_TOKEN_ID = "cabin.auth.demoTokenId";
    public static final String REQUEST_ATTR_DEMO_ROUTE_CLASS = "cabin.auth.demoRouteClass";

    static final String DENIAL_MESSAGE = "Hidden for Demo viewers. Working in the live system, not viewable while previewing.";
    static final String READ_ONLY_MESSAGE = "This is a view-only demo link. Changes can only be made by signed-in household members.";
    static final String INACTIVE_MESSAGE = "This link is no longer active. Access links are time-limited by design. Contact the person who shared it if you still need access.";

    private static final Logger log = LoggerFactory.getLogger("cabin.demo.access");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CabinAccessTokenService accessTokens;
    private final DemoPresenceClassifier presence;

    @Value("${cabin.demo.max-history-days:1}")
    private int maxHistoryDays = 1;

    public DemoAccessFilter(CabinAccessTokenService accessTokens, DemoPresenceClassifier presence) {
        this.accessTokens = accessTokens;
        this.presence = presence;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = extractCabinToken(request);
        if (raw == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<CabinAccessToken> found = accessTokens.lookup(raw);
        if (found.isEmpty() || !found.get().scope().contains(DEMO_SCOPE)) {
            // Not a demo token (or unknown) -- the existing Tier 1 path in
            // GoogleAuthInterceptor owns it, unchanged.
            chain.doFilter(request, response);
            return;
        }
        CabinAccessToken token = found.get();
        String path = request.getRequestURI().substring(request.getContextPath().length());
        applyDemoHeaders(response);

        if (!token.isValid(java.time.Instant.now())) {
            deny(response, 401, "GUEST_LINK_INACTIVE", INACTIVE_MESSAGE, token, path, "INACTIVE");
            return;
        }
        if (!"GET".equalsIgnoreCase(request.getMethod()) && !"HEAD".equalsIgnoreCase(request.getMethod())) {
            deny(response, 403, "GUEST_READ_ONLY", READ_ONLY_MESSAGE, token, path, "WRITE");
            return;
        }
        DemoAccessPolicy.RouteClass routeClass = DemoAccessPolicy.classify(path);
        if (routeClass == DemoAccessPolicy.RouteClass.DENY) {
            deny(response, 403, "GUEST_SCOPE_NOT_INCLUDED", DENIAL_MESSAGE, token, path, "DENY");
            return;
        }
        request.setAttribute(REQUEST_ATTR_DEMO_TOKEN_ID, token.id());
        request.setAttribute(REQUEST_ATTR_DEMO_ROUTE_CLASS, routeClass);

        // R-DM-8: presence-class history is an empty series, never an error.
        if (path.equals("/api/events/telemetry-history")) {
            String deviceId = request.getParameter("deviceId");
            if (deviceId != null && presence.presenceDevices().containsKey(deviceId)) {
                log.info("demo request token={} route={} class={} redacted=series", token.id(), path, routeClass);
                writeJson(response, 200, List.of());
                return;
            }
            request = clampDays(request);
        }
        log.info("demo request token={} route={} class={}", token.id(), path, routeClass);
        chain.doFilter(request, response);
    }

    private HttpServletRequest clampDays(HttpServletRequest request) {
        int max = Math.max(1, maxHistoryDays);
        return new HttpServletRequestWrapper(request) {
            @Override
            public String getParameter(String name) {
                if (!"days".equals(name)) return super.getParameter(name);
                String v = super.getParameter(name);
                int requested;
                try {
                    requested = v == null ? 30 : Integer.parseInt(v.trim());
                } catch (NumberFormatException e) {
                    requested = max;
                }
                return String.valueOf(Math.max(1, Math.min(requested, max)));
            }

            @Override
            public String[] getParameterValues(String name) {
                return "days".equals(name) ? new String[] { getParameter(name) } : super.getParameterValues(name);
            }

            @Override
            public Map<String, String[]> getParameterMap() {
                Map<String, String[]> m = new LinkedHashMap<>(super.getParameterMap());
                m.put("days", new String[] { getParameter("days") });
                return m;
            }
        };
    }

    static void applyDemoHeaders(HttpServletResponse response) {
        response.setHeader("X-Robots-Tag", "noindex, nofollow");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
    }

    private void deny(HttpServletResponse response, int status, String code, String message,
                      CabinAccessToken token, String path, String reason) throws IOException {
        // R-GD: denials log at INFO under a dedicated logger, never as errors.
        log.info("demo denial token={} route={} code={} reason={}", token.id(), path, code, reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("denied_by_design", true);
        body.put("message", message);
        body.put("scope_held", List.of(DEMO_SCOPE));
        writeJson(response, status, body);
    }

    private static void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        // Controllers carry @CrossOrigin (any origin); a response written
        // here never reaches that handler, so without this the browser would
        // report a CORS failure instead of the denial body.
        if (response.getHeader("Access-Control-Allow-Origin") == null) {
            response.setHeader("Access-Control-Allow-Origin", "*");
        }
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JSON.writeValue(response.getOutputStream(), body);
    }

    /** Header only (R-DM-2) -- a demo token is never read from ?t=. */
    private static String extractCabinToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("CabinToken ") && header.length() > 11) {
            return header.substring(11).trim();
        }
        return null;
    }
}
