package com.cabin.orchestrator.security.demo;

import com.cabin.orchestrator.api.EventController;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.events.EventStreamBroadcaster;
import com.cabin.orchestrator.events.TelemetryHistoryResponse;
import com.cabin.orchestrator.security.CabinAccessToken;
import com.cabin.orchestrator.security.CabinAccessTokenService;
import com.cabin.orchestrator.security.CabinAccessTokenStore;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * W-21's required cross-view test (D22 Q-DM-2, answered 2026-09-24: "demo
 * history aligns with the normal view"). Once the demo ceiling
 * (cabin.demo.max-history-days) and the normal-view ceiling
 * (cabin.history.max-days) are both 60, a non-presence device's
 * telemetry-history rows must come back identical whether the caller is a
 * demo-scoped token or an ordinary authenticated caller -- the demo path's
 * only differences from the normal path are presence-series redaction
 * (R-DM-8) and a `days` clamp, neither of which should touch a
 * non-presence device's actual numbers once both ceilings match.
 */
@Testcontainers
class DemoNormalHistoryParityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private EventController controller;
    private DemoAccessFilter filter;
    private String demoToken;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        CabinEventService eventService = new CabinEventService(jdbc);
        controller = new EventController(eventService, new DeviceRegistry(List.of()), new EventStreamBroadcaster());
        ReflectionTestUtils.setField(controller, "maxHistoryDays", 60);

        DemoPresenceClassifier noPresence = new DemoPresenceClassifier(null, null) {
            @Override public Map<String, String> presenceDevices() { return Map.of(); }
        };
        CabinAccessTokenService tokens = new CabinAccessTokenService(new InMemoryStore());
        filter = new DemoAccessFilter(tokens, noPresence);
        ReflectionTestUtils.setField(filter, "maxHistoryDays", 60);
        demoToken = tokens.create("parity check", List.of("demo"), Duration.ofDays(1), "nate@example.com").token();

        Instant now = Instant.now();
        eventService.save(new CabinEvent("evt-1", "z2m-temp_kitchen", "TELEMETRY", "INFO",
            now.minus(Duration.ofDays(1)), Map.of("temperature", 68)));
        eventService.save(new CabinEvent("evt-2", "z2m-temp_kitchen", "TELEMETRY", "INFO",
            now, Map.of("temperature", 72)));
    }

    @Test
    void normalAndDemoViewsReturnIdenticalRowsForANonPresenceDevice() throws Exception {
        TelemetryHistoryResponse direct = controller.telemetryHistory("z2m-temp_kitchen", "temperature", 30);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/events/telemetry-history");
        req.setRequestURI("/api/events/telemetry-history");
        req.addHeader("Authorization", "CabinToken " + demoToken);
        req.setParameter("deviceId", "z2m-temp_kitchen");
        req.setParameter("field", "temperature");
        req.setParameter("days", "30");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        HttpServletRequest wrapped = (HttpServletRequest) chain.getRequest();
        assertNotNull(wrapped, "a non-presence device on an ALLOW_REDACT route must reach the controller");
        int demoDays = Integer.parseInt(wrapped.getParameter("days"));

        TelemetryHistoryResponse viaDemo = controller.telemetryHistory("z2m-temp_kitchen", "temperature", demoDays);

        assertEquals(direct.points(), viaDemo.points(),
            "non-presence device history must match between the normal and demo views (D22 Q-DM-2)");
        assertEquals(direct.effectiveDays(), viaDemo.effectiveDays());
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
