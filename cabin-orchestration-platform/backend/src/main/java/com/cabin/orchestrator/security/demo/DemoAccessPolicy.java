package com.cabin.orchestrator.security.demo;

import org.springframework.util.AntPathMatcher;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * D22 R-DM-4 -- classifies every GET route a demo-token caller may reach.
 * Deny by default: a path with no entry here is DENY, and
 * DemoAccessPolicyTest enumerates every mapped GET handler in the codebase
 * and fails when one has no entry, so a future route must be classified
 * the day it is added rather than silently falling open or closed.
 *
 * Order matters: the first matching pattern wins, so specific literals
 * (/api/devices/candidates) come before the templates they would otherwise
 * match (/api/devices/{id}).
 */
public final class DemoAccessPolicy {

    public enum RouteClass { ALLOW, ALLOW_REDACT, DENY }

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private static final Map<String, RouteClass> TABLE = new LinkedHashMap<>();

    private static void put(RouteClass c, String... patterns) {
        for (String p : patterns) TABLE.put(p, c);
    }

    static {
        // Explicit DENY entries that would otherwise be shadowed by a broader
        // ALLOW_REDACT template below.
        put(RouteClass.DENY,
            "/api/devices/candidates", "/api/devices/previously-exposed",
            "/api/devices/{id}/config", "/api/devices/{id}/discovery/latest", "/api/devices/{id}/jsonld",
            "/api/events/live");

        put(RouteClass.ALLOW,
            "/api/dashboard/config", "/api/locations", "/api/system/health", "/api/system/platform-info",
            "/api/ontology/entities", "/api/context/cabin-context.jsonld",
            "/api/devices/meta/types", "/api/devices/meta/lifecycle", "/api/devices/display-config",
            "/api/rules/vocabulary/triggers", "/api/rules/vocabulary/actions");

        put(RouteClass.ALLOW_REDACT,
            "/api/devices", "/api/devices/reporting-relationships", "/api/devices/checkin-status",
            "/api/devices/checkin-details", "/api/devices/{id}", "/api/devices/{id}/display-config",
            "/api/alerts/active", "/api/alerts/rules", "/api/alerts/acknowledgments",
            "/api/events/telemetry-history", "/api/events/reported-fields",
            "/api/rules/workflows", "/api/rules/workflows/{id}", "/api/rules/workflows/{id}/executions",
            "/api/rules/executions/recent", "/api/signal-quality");

        put(RouteClass.DENY,
            "/api/events", "/api/camera/**", "/api/frigate-metrics/**", "/api/frigate-metrics",
            "/api/security/**", "/api/security", "/api/presence/**", "/api/presence",
            "/api/kb/**", "/api/helpdesk/**", "/api/opportunities/**", "/api/opportunities",
            "/api/cross-domain/**", "/api/platform-import/**", "/api/tech-id/**",
            "/api/managed-users/**", "/api/access-tokens/**", "/api/access-tokens",
            "/api/auth/**", "/api/webhooks/**",
            "/api/profiles/**", "/api/profiles", "/api/chores/**", "/api/chores",
            "/api/notes/**", "/api/notes", "/api/schedule/**", "/api/schedule");
    }

    private DemoAccessPolicy() {}

    /** First matching entry, or DENY when nothing matches. */
    public static RouteClass classify(String path) {
        for (var e : TABLE.entrySet()) {
            if (MATCHER.match(e.getKey(), path)) return e.getValue();
        }
        return RouteClass.DENY;
    }

    /** True when some table entry (not the deny-by-default fallthrough) covers this route template. */
    public static boolean hasEntry(String routeTemplate) {
        String normalized = routeTemplate.replaceAll("\\{[^}]+}", "x-placeholder");
        return TABLE.keySet().stream().anyMatch(p -> MATCHER.match(p, normalized));
    }
}
