package com.cabin.orchestrator.security;

import com.cabin.orchestrator.api.TelemetryExportController;
import com.cabin.orchestrator.events.TelemetryExportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The full-history telemetry export must never be open the way /api/events once was (2026-09-01 exposure). This runs
 * the REAL GoogleAuthInterceptor with a real credential of each kind in front of the REAL controller, through Spring's
 * own routing (MockMvc), and checks that only a signed-in administrator gets data. The export service is a mock so
 * "refused" is provable as "the service was never called".
 */
class TelemetryExportAccessTest {

    private TelemetryExportService service;
    private CabinSessionService cabinSessions;
    private ManagedUserService managedUsers;
    private CabinAccessTokenService accessTokens;
    private MockMvc mvc;
    private final List<String> sentUrls = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        sentUrls.clear();
        GoogleAuthInterceptorTest fixtures = new GoogleAuthInterceptorTest();
        accessTokens = new CabinAccessTokenService(fixtures.new InMemoryAccessTokenStore());
        managedUsers = new ManagedUserService(new GoogleAuthInterceptorTest.InMemoryManagedUserStore(),
            new GoogleAuthInterceptorTest.InMemoryMagicLinkTokenStore(),
            new GoogleAuthInterceptorTest.InMemoryManagedUserSessionStore(), (to, name, url) -> sentUrls.add(url));
        ReflectionTestUtils.setField(managedUsers, "frontendOrigin", "http://localhost:5173");
        cabinSessions = new CabinSessionService(new GoogleAuthInterceptorTest.InMemoryCabinSessionStore());
        GoogleAuthInterceptor interceptor = new GoogleAuthInterceptor(accessTokens, managedUsers, cabinSessions);
        ReflectionTestUtils.setField(interceptor, "adminEmailsRaw", "admin@example.com");

        service = mock(TelemetryExportService.class);
        when(service.export(any(), any())).thenAnswer(inv -> {
            OutputStream out = inv.getArgument(1);
            out.write("{\"eventId\":\"x\"}\n".getBytes(StandardCharsets.UTF_8));
            return new TelemetryExportService.Result(1, 0, 0);
        });
        mvc = MockMvcBuilders.standaloneSetup(new TelemetryExportController(service)).addInterceptors(interceptor).build();
    }

    private String managedToken(ManagedUserRole role) {
        ManagedUser user = managedUsers.create(role.name().toLowerCase() + "@example.com", "Test", role, "admin@example.com");
        managedUsers.invite(user.id());
        String url = sentUrls.get(sentUrls.size() - 1);
        return managedUsers.consumeMagicLink(url.substring(url.lastIndexOf('/') + 1)).orElseThrow().token();
    }

    private int status(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void anUnauthenticatedCallIsRefusedAndNothingIsExported() throws Exception {
        assertEquals(401, status(get("/api/events/export")));
        assertEquals(401, status(get("/api/events/export").param("from", "2023-01-01")));
        // a made-up or expired credential is no better than none
        assertEquals(401, status(get("/api/events/export").header("Authorization", "CabinSession not-a-real-session")));
        assertEquals(401, status(get("/api/events/export").header("Authorization", "ManagedSession not-a-real-session")));
        assertEquals(401, status(get("/api/events/export").header("Authorization", "CabinToken not-a-real-link")));
        verify(service, never()).export(any(), any());
    }

    @Test
    void everyCredentialThatIsNotAnAdministratorIsForbidden() throws Exception {
        String member = cabinSessions.issue("member@example.com").token();
        assertEquals(403, status(get("/api/events/export").header("Authorization", "CabinSession " + member)));

        String household = managedToken(ManagedUserRole.HOUSEHOLD_MEMBER);
        assertEquals(403, status(get("/api/events/export").header("Authorization", "ManagedSession " + household)));
        String viewer = managedToken(ManagedUserRole.VIEWER);
        assertEquals(403, status(get("/api/events/export").header("Authorization", "ManagedSession " + viewer)));

        // a guest link carrying every scope there is still does not cover the export (its path is not in any scope)
        String guest = accessTokens.create("g", List.of("dashboard", "device_states", "alerts_read", "observations_read"), null, "admin@example.com").token();
        assertEquals(403, status(get("/api/events/export").header("Authorization", "CabinToken " + guest)));
        assertEquals(403, status(get("/api/events/export").param("t", guest)));
        verify(service, never()).export(any(), any());
    }

    @Test
    void anAdministratorGetsTheStreamWithDownloadHeaders() throws Exception {
        String admin = cabinSessions.issue("admin@example.com").token();
        var result = mvc.perform(get("/api/events/export").header("Authorization", "CabinSession " + admin)
            .param("from", "2023-11-14").param("format", "jsonl")).andReturn().getResponse();

        assertEquals(200, result.getStatus());
        assertEquals("{\"eventId\":\"x\"}\n", result.getContentAsString());
        assertEquals("application/x-ndjson", result.getContentType());
        assertTrue(result.getHeader("Content-Disposition").startsWith("attachment"));
        assertEquals("no-store", result.getHeader("Cache-Control"));
        verify(service).export(any(), any());
    }

    @Test
    void badParametersAreA400BeforeAnyByteIsStreamed() throws Exception {
        String admin = cabinSessions.issue("admin@example.com").token();
        String auth = "CabinSession " + admin;
        assertEquals(400, status(get("/api/events/export").header("Authorization", auth).param("format", "xlsx")));
        assertEquals(400, status(get("/api/events/export").header("Authorization", auth).param("from", "yesterday")));
        assertEquals(400, status(get("/api/events/export").header("Authorization", auth)
            .param("from", "2026-09-01").param("to", "2026-08-01")));
        verify(service, never()).export(any(), any());
    }
}
