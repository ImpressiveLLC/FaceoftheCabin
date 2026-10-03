package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.pattern.PathPatternParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W-31 (P0): the permanent form of the controller audit that found
 * POST /api/devices/{id}/command reachable with no credential at all.
 *
 * The bug was structural, not a bad check: /api/devices/** was simply never
 * in WebConfig's interceptor list, so none of its write routes ran any auth
 * code. This test walks every @RestController, takes every route that can
 * change state (anything but GET/HEAD/OPTIONS, plus any mapping that accepts
 * every verb), and requires it to be one of:
 *
 *   1. covered by a WebConfig interceptor pattern AND refused for an
 *      anonymous caller by GoogleAuthInterceptor, or
 *   2. on a short, documented allowlist below -- with the reason it is open.
 *
 * A new controller, a new write route, or a carve-out that quietly opens an
 * existing one fails here until someone decides on purpose. Allowlist entries
 * that stop being true also fail, so a fixed gap cannot linger as a stale
 * exemption.
 */
class WriteGateAuditTest {

    record Route(String method, String template) {
        /** Path with {placeholders} replaced, so it can be matched and requested. */
        String path() { return template.replaceAll("\\{[^}]+}", "x"); }
        String key() { return method + " " + template; }
    }

    /** Routes with no WebConfig pattern, deliberately open. Each must stay true or be removed. */
    private static final Map<String, String> OPEN_BY_DESIGN = Map.of(
        "POST /api/auth/session/revoke",
            "signing out only requires holding the token being invalidated; same contract as any logout",
        "POST /api/webhooks/blink-motion",
            "own shared-secret header (cabin.blinkMotionWebhook.apiKey); returns 503/401 when unset or wrong");

    /** Routes with no WebConfig pattern that are an OPEN KNOWN GAP, tracked by a backlog item. Remove each entry when its item lands. */
    private static final Map<String, String> KNOWN_GAP = Map.of(
        "POST /api/locations", "W-32",
        "PATCH /api/locations/{id}", "W-32",
        "POST /api/locations/reorder", "W-32",
        "DELETE /api/locations/{id}", "W-32");

    /** Routes inside a WebConfig pattern that GoogleAuthInterceptor lets through on purpose, each with its own check. */
    private static final Map<String, String> CARVED_OUT = Map.of(
        "POST /api/tech-id/findings",
            "automated providers submit with a shared-secret X-Tech-Id-Api-Key; fails closed when unset",
        "POST /api/managed-users/magic/{token}/consume",
            "a managed user has no Google account; the single-use magic-link token is the credential");

    /** Every state-changing route in the backend, found by scanning the real controllers. */
    static List<Route> nonGetRoutes() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Route> routes = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cabin.orchestrator")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] bases = classMapping == null || classMapping.path().length == 0 ? new String[] { "" } : classMapping.path();
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null) continue;
                List<String> verbs = new ArrayList<>();
                if (mm.method().length == 0) {
                    verbs.add("ANY");
                } else {
                    for (RequestMethod rm : mm.method()) {
                        if (rm != RequestMethod.GET && rm != RequestMethod.HEAD && rm != RequestMethod.OPTIONS) verbs.add(rm.name());
                    }
                }
                String[] subs = mm.path().length == 0 ? new String[] { "" } : mm.path();
                for (String verb : verbs) for (String base : bases) for (String sub : subs) {
                    routes.add(new Route(verb, base + sub));
                }
            }
        }
        return routes;
    }

    /** The patterns WebConfig really registers for GoogleAuthInterceptor, read back from the registry. */
    static List<String> registeredPatterns() {
        class Recording extends InterceptorRegistry {
            List<String> patterns() {
                List<String> out = new ArrayList<>();
                for (Object o : getInterceptors()) {
                    if (o instanceof MappedInterceptor mi && mi.getPathPatterns() != null) {
                        out.addAll(Arrays.asList(mi.getPathPatterns()));
                    }
                }
                return out;
            }
        }
        WebConfig config = new WebConfig(new GoogleAuthInterceptor(null, null, null));
        ReflectionTestUtils.setField(config, "googleAuthEnabled", true);
        Recording registry = new Recording();
        config.addInterceptors(registry);
        return registry.patterns();
    }

    static boolean matchesAnyPattern(List<String> patterns, String path) {
        PathContainer container = PathContainer.parsePath(path);
        return patterns.stream().anyMatch(p -> PathPatternParser.defaultInstance.parse(p).matches(container));
    }

    private static String verbForRequest(Route r) { return r.method().equals("ANY") ? "POST" : r.method(); }

    /** What GoogleAuthInterceptor does for a request carrying no credential at all. */
    private static MockHttpServletResponse anonymousVerdict(Route r, boolean[] allowedOut) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(verbForRequest(r), r.path());
        request.setRequestURI(r.path());
        MockHttpServletResponse response = new MockHttpServletResponse();
        allowedOut[0] = new GoogleAuthInterceptor(null, null, null).preHandle(request, response, new Object());
        return response;
    }

    @Test
    void theScanActuallyFindsTheBackendsWriteRoutes() throws Exception {
        List<Route> routes = nonGetRoutes();
        assertTrue(routes.size() >= 60, "expected to find the app's ~70 write routes, found " + routes.size());
        assertTrue(routes.stream().anyMatch(r -> r.key().equals("POST /api/devices/{deviceId}/command")),
            "the route W-31 was raised for must be in the scan");
        assertFalse(registeredPatterns().isEmpty(), "WebConfig registered no interceptor patterns");
    }

    @Test
    void everyWriteRouteIsGatedOrDocumentedOpen() throws Exception {
        List<String> patterns = registeredPatterns();
        List<String> unprotected = new ArrayList<>();
        List<String> notRefusedAnonymously = new ArrayList<>();
        for (Route r : nonGetRoutes()) {
            boolean covered = matchesAnyPattern(patterns, r.path());
            if (!covered) {
                if (!OPEN_BY_DESIGN.containsKey(r.key()) && !KNOWN_GAP.containsKey(r.key())) unprotected.add(r.key());
                continue;
            }
            boolean[] allowed = new boolean[1];
            MockHttpServletResponse response = anonymousVerdict(r, allowed);
            boolean refused = !allowed[0] && response.getStatus() == 401;
            if (!refused && !CARVED_OUT.containsKey(r.key())) {
                notRefusedAnonymously.add(r.key() + " -> allowed=" + allowed[0] + " status=" + response.getStatus());
            }
        }
        assertEquals(List.of(), unprotected,
            "State-changing routes with NO interceptor coverage and no documented reason (W-31). "
                + "Add the path to WebConfig, or list it in OPEN_BY_DESIGN with the reason it is open:");
        assertEquals(List.of(), notRefusedAnonymously,
            "Routes inside a WebConfig pattern that an anonymous caller still gets through. "
                + "Gate it, or list it in CARVED_OUT with the check that protects it:");
    }

    @Test
    void theAllowlistsHaveNoStaleEntries() throws Exception {
        List<String> patterns = registeredPatterns();
        TreeSet<String> uncovered = new TreeSet<>();
        TreeSet<String> covered = new TreeSet<>();
        for (Route r : nonGetRoutes()) {
            (matchesAnyPattern(patterns, r.path()) ? covered : uncovered).add(r.key());
        }
        List<String> stale = new ArrayList<>();
        for (String k : OPEN_BY_DESIGN.keySet()) if (!uncovered.contains(k)) stale.add("OPEN_BY_DESIGN " + k);
        for (String k : KNOWN_GAP.keySet()) if (!uncovered.contains(k)) stale.add("KNOWN_GAP " + k + " (" + KNOWN_GAP.get(k) + " landed? remove the entry)");
        for (String k : CARVED_OUT.keySet()) if (!covered.contains(k)) stale.add("CARVED_OUT " + k);
        assertEquals(List.of(), stale, "Allowlist entries that no longer describe a real route -- remove them:");
    }

    @Test
    void everyDevicesWriteRouteIsCoveredByTheInterceptor() throws Exception {
        List<String> patterns = registeredPatterns();
        List<Route> devices = nonGetRoutes().stream().filter(r -> r.template().startsWith("/api/devices")).toList();
        assertTrue(devices.size() >= 14, "expected the 14 /api/devices write routes, found " + devices.size());
        for (Route r : devices) {
            assertTrue(matchesAnyPattern(patterns, r.path()), r.key() + " is not covered by WebConfig (W-31)");
        }
    }
}
