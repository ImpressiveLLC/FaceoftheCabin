package com.cabin.orchestrator.security.demo;

import com.cabin.orchestrator.security.AccessEvent;
import com.cabin.orchestrator.security.CabinAccessToken;
import com.cabin.orchestrator.security.CabinAccessTokenService;
import com.cabin.orchestrator.security.CabinAccessTokenStore;
import com.cabin.orchestrator.security.CabinAccessTokensController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Access link usage tracking (HO-2026-10-29), demo half and the admin read API. */
class DemoAccessUsageTrackingTest {

    private record Use(String tokenId, String tier, String route, String policyClass, int status,
                       String clientIp, String userAgent, boolean pageOpen) { }

    private final List<Use> uses = new ArrayList<>();
    private boolean failWrites;
    private CabinAccessTokenService tokens;
    private DemoAccessFilter filter;

    @BeforeEach
    void setUp() {
        tokens = new CabinAccessTokenService(new RecordingStore());
        DemoPresenceClassifier classifier = new DemoPresenceClassifier(null, null) {
            @Override public Map<String, String> presenceDevices() { return Map.of(); }
        };
        filter = new DemoAccessFilter(tokens, classifier);
        ReflectionTestUtils.setField(filter, "maxHistoryDays", 60);
    }

    private CabinAccessToken demoLink() {
        return tokens.create("Agent preview", List.of("demo"), Duration.ofDays(30), "nate@example.com");
    }

    private MockHttpServletResponse call(String path, CabinAccessToken link, String... headers) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.addHeader("Authorization", "CabinToken " + link.token());
        req.addHeader("CF-Connecting-IP", "198.51.100.7");
        req.addHeader("User-Agent", "AgentBrowser/2");
        for (int i = 0; i + 1 < headers.length; i += 2) req.addHeader(headers[i], headers[i + 1]);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        return res;
    }

    @Test
    void aDemoRequestIsRecordedWithTheLinkIdAndNeverTheTokenString() throws Exception {
        CabinAccessToken link = demoLink();

        assertEquals(200, call("/api/dashboard/config", link).getStatus());

        assertEquals(1, uses.size());
        Use use = uses.get(0);
        assertEquals(link.id(), use.tokenId());
        assertEquals("demo", use.tier());
        assertEquals("/api/dashboard/config", use.route());
        assertEquals("ALLOW", use.policyClass());
        assertEquals(200, use.status());
        assertEquals("198.51.100.7", use.clientIp());
        assertEquals("AgentBrowser/2", use.userAgent());
        assertFalse(use.pageOpen());
        for (String field : List.of(use.tokenId(), use.tier(), use.route(), use.policyClass(), use.clientIp(), use.userAgent())) {
            assertFalse(field.contains(link.token()), "the bearer token must never be stored");
        }
    }

    @Test
    void aDeniedDemoRouteIsRecordedAs403() throws Exception {
        CabinAccessToken link = demoLink();

        assertEquals(403, call("/api/camera/list", link).getStatus());

        assertEquals(1, uses.size());
        assertEquals(403, uses.get(0).status());
        assertEquals("DENY", uses.get(0).policyClass());
        assertEquals("/api/camera/list", uses.get(0).route());
    }

    @Test
    void theDemoPageOpenCallIsItsOwnEvent() throws Exception {
        CabinAccessToken link = demoLink();

        call("/api/dashboard/config", link, "X-Demo-Page-Open", "1");

        assertEquals(1, uses.size());
        assertEquals(AccessEvent.ROUTE_PAGE_OPEN, uses.get(0).route());
        assertTrue(uses.get(0).pageOpen());
    }

    @Test
    void aTokenInTheRouteIsMaskedBeforeItIsStored() throws Exception {
        CabinAccessToken link = demoLink();
        tokens.recordUse(link, "view", "/api/x/" + link.token() + "/y", "GUEST", 200, "1.2.3.4", "ua", false);

        assertEquals("/api/x/[token]/y", uses.get(0).route());
    }

    @Test
    void aFailureToRecordNeverBreaksTheRequest() throws Exception {
        CabinAccessToken link = demoLink();
        failWrites = true;

        assertEquals(200, call("/api/dashboard/config", link).getStatus());
        assertEquals(0, uses.size());
    }

    @Test
    void aDemoLinkStillGets403OnTheAdminActivityRoute() throws Exception {
        CabinAccessToken link = demoLink();

        assertEquals(403, call("/api/access-tokens/" + link.id() + "/activity", link).getStatus());
        assertEquals(403, call("/api/access-tokens", link).getStatus());
    }

    @Test
    void theAdminActivityRouteReturnsTheLinksEvents() {
        CabinAccessToken link = demoLink();
        CabinAccessTokensController controller = new CabinAccessTokensController(tokens);

        assertEquals(List.of(), controller.activity(link.id(), 30));
    }

    private final class RecordingStore implements CabinAccessTokenStore {
        private final Map<String, CabinAccessToken> byToken = new HashMap<>();
        @Override public List<CabinAccessToken> loadAll() { return new ArrayList<>(byToken.values()); }
        @Override public Optional<CabinAccessToken> findByToken(String token) { return Optional.ofNullable(byToken.get(token)); }
        @Override public void save(CabinAccessToken token) { byToken.put(token.token(), token); }
        @Override public void revoke(String id, Instant revokedAt) { }
        @Override public void recordUse(String tokenId, Instant at, String tier, String route, String policyClass,
                                        int status, String clientIp, String userAgent, boolean pageOpen) {
            if (failWrites) throw new IllegalStateException("database down");
            uses.add(new Use(tokenId, tier, route, policyClass, status, clientIp, userAgent, pageOpen));
        }
    }
}
