package com.cabin.orchestrator.security.demo;

import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.security.CabinAccessToken;
import com.cabin.orchestrator.security.CabinAccessTokenService;
import com.cabin.orchestrator.security.CabinAccessTokenStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** D22's eight required tests, backend half (1-7). Test 8 is in the UI suite (App.test.jsx). */
class DemoAccessTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private CabinAccessTokenService tokens;
    private DemoAccessFilter filter;
    private Map<String, String> presenceSet;

    @BeforeEach
    void setUp() {
        tokens = new CabinAccessTokenService(new InMemoryStore());
        presenceSet = new HashMap<>(Map.of("lock.front_door", "Front Door Lock"));
        DemoPresenceClassifier classifier = new DemoPresenceClassifier(null, null) {
            @Override public Map<String, String> presenceDevices() { return presenceSet; }
        };
        filter = new DemoAccessFilter(tokens, classifier);
        ReflectionTestUtils.setField(filter, "maxHistoryDays", 1);
    }

    private String demoToken() {
        return tokens.create("Agent preview", List.of("demo"), Duration.ofDays(30), "nate@example.com").token();
    }

    private MockHttpServletResponse run(String method, String path, String token, MockFilterChain chain) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.setRequestURI(path);
        if (token != null) req.addHeader("Authorization", "CabinToken " + token);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    // ── 1. Every mapped GET handler has a policy entry ──────────────────────

    @Test
    void everyMappedGetRouteIsClassified() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        TreeSet<String> routes = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.cabin.orchestrator")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            RequestMapping classMapping = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] bases = classMapping == null || classMapping.path().length == 0 ? new String[] { "" } : classMapping.path();
            for (Method m : type.getDeclaredMethods()) {
                RequestMapping mm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (mm == null) continue;
                boolean isGet = mm.method().length == 0 || List.of(mm.method()).contains(RequestMethod.GET);
                if (!isGet) continue;
                String[] subs = mm.path().length == 0 ? new String[] { "" } : mm.path();
                for (String base : bases) for (String sub : subs) routes.add(base + sub);
            }
        }
        assertTrue(routes.size() > 30, "expected to find the app's GET routes, found " + routes);
        List<String> unclassified = routes.stream().filter(r -> r.startsWith("/api/"))
            .filter(r -> !DemoAccessPolicy.hasEntry(r)).toList();
        assertEquals(List.of(), unclassified,
            "Every GET route needs a DemoAccessPolicy entry (D22 R-DM-4). Classify these as ALLOW, ALLOW_REDACT or DENY:");
    }

    @Test
    void unknownRoutesDenyByDefault() {
        assertEquals(DemoAccessPolicy.RouteClass.DENY, DemoAccessPolicy.classify("/api/some-future-thing"));
        assertEquals(DemoAccessPolicy.RouteClass.DENY, DemoAccessPolicy.classify("/api/devices/candidates"));
        assertEquals(DemoAccessPolicy.RouteClass.ALLOW_REDACT, DemoAccessPolicy.classify("/api/devices/abc.def"));
        assertEquals(DemoAccessPolicy.RouteClass.ALLOW_REDACT, DemoAccessPolicy.classify("/api/devices"));
    }

    // ── 2. Writes are refused ───────────────────────────────────────────────

    @Test
    void everyWriteMethodIsReadOnlyDenied() throws Exception {
        String t = demoToken();
        for (String method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            for (String path : List.of("/api/rules/workflows", "/api/devices/x/config", "/api/alerts/acknowledgments",
                                       "/api/notes", "/api/access-tokens", "/api/devices/x/lifecycle")) {
                MockFilterChain chain = new MockFilterChain();
                MockHttpServletResponse res = run(method, path, t, chain);
                assertEquals(403, res.getStatus(), method + " " + path);
                assertEquals("GUEST_READ_ONLY", JSON.readTree(res.getContentAsString()).get("code").asText());
                assertNull(chain.getRequest(), "a write must never reach the controller");
            }
        }
    }

    // ── 3. DENY routes return the R-GD body ─────────────────────────────────

    @Test
    void denyRoutesReturnTheDenialContract() throws Exception {
        String t = demoToken();
        for (String path : List.of("/api/events", "/api/events/live", "/api/camera/list", "/api/camera/front/live",
                                   "/api/frigate-metrics", "/api/security", "/api/presence", "/api/presence/activity",
                                   "/api/kb/nodes", "/api/helpdesk/ask", "/api/opportunities", "/api/cross-domain/cabin-status-summary",
                                   "/api/platform-import/records", "/api/tech-id/findings", "/api/managed-users",
                                   "/api/access-tokens", "/api/auth/session", "/api/devices/candidates",
                                   "/api/devices/previously-exposed", "/api/devices/x/config", "/api/devices/x/discovery/latest",
                                   "/api/devices/x/jsonld", "/api/profiles", "/api/chores/completion", "/api/notes", "/api/schedule")) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse res = run("GET", path, t, chain);
            assertEquals(403, res.getStatus(), path);
            JsonNode body = JSON.readTree(res.getContentAsString());
            assertEquals("GUEST_SCOPE_NOT_INCLUDED", body.get("code").asText(), path);
            assertTrue(body.get("denied_by_design").asBoolean());
            assertEquals(DemoAccessFilter.DENIAL_MESSAGE, body.get("message").asText());
            assertEquals("no-store", res.getHeader("Cache-Control"));
            assertEquals("noindex, nofollow", res.getHeader("X-Robots-Tag"));
            assertNull(chain.getRequest(), path + " must not reach the controller");
        }
    }

    @Test
    void allowedRouteReachesControllerMarkedForRedaction() throws Exception {
        MockFilterChain chain = new MockFilterChain();
        MockHttpServletResponse res = run("GET", "/api/devices", demoToken(), chain);
        assertEquals(200, res.getStatus());
        assertEquals(DemoAccessPolicy.RouteClass.ALLOW_REDACT,
            chain.getRequest().getAttribute(DemoAccessFilter.REQUEST_ATTR_DEMO_ROUTE_CLASS));
        assertEquals("no-referrer", res.getHeader("Referrer-Policy"));
    }

    @Test
    void nonDemoCallersPassThroughUntouched() throws Exception {
        String tier1 = tokens.create("Tier 1", List.of("device_states"), null, "nate@example.com").token();
        for (String token : new String[] { null, tier1, "not-a-real-token" }) {
            MockFilterChain chain = new MockFilterChain();
            run("GET", "/api/events", token, chain);
            assertNotNull(chain.getRequest());
            assertNull(chain.getRequest().getAttribute(DemoAccessFilter.REQUEST_ATTR_DEMO_ROUTE_CLASS));
        }
    }

    // ── 4. Redaction ────────────────────────────────────────────────────────

    @Test
    void presenceClassRulesMatchR_DM_6() {
        List<DeviceReportingRelationship> none = List.of();
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("camera.front", DeviceType.CAMERA, "Front Cam", "cabin"), none));
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("lock.front", DeviceType.LOCK, "Front Lock", "cabin"), none));
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("pir.hall", DeviceType.MOTION_SENSOR, "Hall PIR", "cabin"), none));
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("door.back", DeviceType.CONTACT_SENSOR, "Back Door", "cabin"), none));
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("zb.multi", DeviceType.HOME_ASSISTANT_ENTITY, "Multi", "cabin"),
            List.of(new DeviceReportingRelationship("zb.multi", "occupancy", "occupancy", ConfirmationSource.values()[0], Instant.now()))));
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("sensor.pixel_8_battery_level", DeviceType.HOME_ASSISTANT_ENTITY, "Pixel 8 Battery", "cabin"), none));
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("device_tracker.nate", DeviceType.HOME_ASSISTANT_ENTITY, "Nate", "cabin"), none));
        assertTrue(DemoPresenceClassifier.isPresenceClass(dev("climate.home_ecobee", DeviceType.THERMOSTAT, "Ecobee", "home"), none));

        assertFalse(DemoPresenceClassifier.isPresenceClass(dev("leak.kitchen", DeviceType.WATER_LEAK_SENSOR, "Kitchen Leak", "cabin"),
            List.of(new DeviceReportingRelationship("leak.kitchen", "water_leak", "water_leak", ConfirmationSource.values()[0], Instant.now()))));
        assertFalse(DemoPresenceClassifier.isPresenceClass(dev("temp.living", DeviceType.TEMPERATURE_SENSOR, "Living Room Temp", "cabin"), none));
        assertFalse(DemoPresenceClassifier.isPresenceClass(dev("valve.main", DeviceType.HOME_ASSISTANT_ENTITY, "Main Water Valve", "cabin"), none));
    }

    @Test
    void devicesListRedactsPresenceRowsAndLeavesOthers() {
        presenceSet.put("camera.front", "Front Cam");
        List<DeviceStatus> list = List.of(
            dev("lock.front_door", DeviceType.LOCK, "Front Door Lock", "cabin"),
            dev("camera.front", DeviceType.CAMERA, "Front Cam", "cabin"),
            dev("leak.kitchen", DeviceType.WATER_LEAK_SENSOR, "Kitchen Leak", "cabin"),
            dev("temp.living", DeviceType.TEMPERATURE_SENSOR, "Living Room Temp", "cabin"));
        JsonNode before = JSON.valueToTree(list);
        JsonNode out = DemoRedactor.redact(JSON.valueToTree(list), presenceSet, new int[1]);

        for (int i : new int[] { 0, 1 }) {
            JsonNode row = out.get(i);
            assertEquals(DemoRedactor.HIDDEN_FOR_DEMO, row.get("name").asText());
            assertTrue(row.get("lastSeen").isNull());
            assertEquals("ONLINE", row.get("state").asText());
            assertEquals(before.get(i).get("deviceId"), row.get("deviceId"));
            assertEquals(before.get(i).get("type"), row.get("type"));
            row.get("attributes").fields().forEachRemaining(e -> assertEquals(DemoRedactor.HIDDEN_FOR_DEMO, e.getValue().asText()));
        }
        assertEquals(before.get(2), out.get(2), "leak sensor row is unmodified");
        assertEquals(before.get(3), out.get(3), "climate sensor row is unmodified");
    }

    @Test
    void freeTextNamingAPresenceDeviceIsRedacted() {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("deviceId", "leak.kitchen");
        alert.put("message", "Front Door Lock battery low; Kitchen Leak dry");
        Map<String, Object> keyed = Map.of("lock.front_door", List.of("battery", "locked"), "leak.kitchen", List.of("water_leak"));
        JsonNode out = DemoRedactor.redact(JSON.valueToTree(Map.of("alert", alert, "fields", keyed)), presenceSet, new int[1]);
        assertEquals("hidden for Demo viewers battery low; Kitchen Leak dry", out.at("/alert/message").asText());
        assertEquals(0, out.at("/fields/lock.front_door").size());
        assertEquals(1, out.at("/fields/leak.kitchen").size());
    }

    // ── 5. History ──────────────────────────────────────────────────────────

    @Test
    void presenceHistoryIsAnEmptySeriesAndDaysAreClamped() throws Exception {
        String t = demoToken();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/events/telemetry-history");
        req.setRequestURI("/api/events/telemetry-history");
        req.addHeader("Authorization", "CabinToken " + t);
        req.setParameter("deviceId", "lock.front_door");
        req.setParameter("field", "battery");
        req.setParameter("days", "90");
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, res, chain);
        assertEquals(200, res.getStatus());
        assertEquals("[]", res.getContentAsString());
        assertNull(chain.getRequest());

        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/events/telemetry-history");
        req2.setRequestURI("/api/events/telemetry-history");
        req2.addHeader("Authorization", "CabinToken " + t);
        req2.setParameter("deviceId", "temp.living");
        req2.setParameter("field", "temperature");
        req2.setParameter("days", "90");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(req2, new MockHttpServletResponse(), chain2);
        assertEquals("1", ((HttpServletRequest) chain2.getRequest()).getParameter("days"));
    }

    // ── 6. Creation rules ───────────────────────────────────────────────────

    @Test
    void demoScopeCannotBeMixedAndAlwaysExpires() {
        assertThrows(IllegalArgumentException.class,
            () -> tokens.create("mixed", List.of("demo", "device_states"), Duration.ofDays(1), "nate@example.com"));
        CabinAccessToken t = tokens.create("no expiry given", List.of("demo"), null, "nate@example.com");
        assertNotNull(t.expiresAt());
    }

    // ── 7. Inactive links ───────────────────────────────────────────────────

    @Test
    void expiredAndRevokedDemoTokensAreInactive() throws Exception {
        CabinAccessToken revoked = tokens.create("revoked", List.of("demo"), Duration.ofDays(1), "nate@example.com");
        tokens.revoke(revoked.id());
        CabinAccessToken expired = tokens.create("expired", List.of("demo"), Duration.ofDays(-1), "nate@example.com");
        for (String raw : List.of(revoked.token(), expired.token())) {
            MockFilterChain chain = new MockFilterChain();
            MockHttpServletResponse res = run("GET", "/api/devices", raw, chain);
            assertEquals(401, res.getStatus());
            assertEquals("GUEST_LINK_INACTIVE", JSON.readTree(res.getContentAsString()).get("code").asText());
            assertNull(chain.getRequest());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static DeviceStatus dev(String id, DeviceType type, String name, String location) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("battery", 88);
        attrs.put("friendly", name);
        return new DeviceStatus(id, type, name, "ONLINE", Instant.parse("2026-09-23T12:00:00Z"), attrs, location);
    }

    private static final class InMemoryStore implements CabinAccessTokenStore {
        private final Map<String, CabinAccessToken> byToken = new HashMap<>();
        @Override public List<CabinAccessToken> loadAll() { return new ArrayList<>(byToken.values()); }
        @Override public Optional<CabinAccessToken> findByToken(String token) { return Optional.ofNullable(byToken.get(token)); }
        @Override public void save(CabinAccessToken token) { byToken.put(token.token(), token); }
        @Override public void revoke(String id, Instant revokedAt) {
            byToken.replaceAll((t, e) -> e.id().equals(id)
                ? new CabinAccessToken(e.id(), e.token(), e.label(), e.scope(), e.expiresAt(), revokedAt, e.createdBy(), e.createdAt())
                : e);
        }
    }
}
