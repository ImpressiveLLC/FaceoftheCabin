package com.cabin.orchestrator.security;

import com.cabin.orchestrator.security.demo.DemoAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The role-by-area table in docs/ai-assistant/user-guide/access-and-storage.md ("Who can see and change what") is checked against
 * the code, so the guide and the policy cannot drift (W-137; same family as WriteGateAuditTest and AdministratorRouteAuditTest).
 *
 * <p>Each area names one representative route. Whether a role may reach it is computed by running the REAL GoogleAuthInterceptor with
 * a real credential of that kind (an administrator and an other-Google CabinSession, a managed household member and a managed viewer
 * ManagedSession, a guest link with every scope ticked), then applying the handler's own role gate, which this test ties to the
 * controller by requiring the gate's refusal message to still be in the controller source. The demo link is classified by
 * DemoAccessPolicy. Cell letters: R read, W change, U use, - none; a star on a guest cell means "only for the parts ticked".
 * A controller that checks the administrator role and is not named by any area fails the completeness test: add a row.
 */
class RoleMatrixTest {

    enum Gate { NONE, ADMIN, ADMIN_OR_ADULT }

    /** read/change = "METHOD /path" or null; kind U means the one operation is "use" rather than read. */
    record Area(String name, String read, String change, Gate gate, String controller, String marker, boolean use) {}

    static final String ADMIN_MARKER = AdministratorOnly.MESSAGE;

    static final List<Area> AREAS = List.of(
        new Area("Status checks and alerts", "GET /api/alerts/active", "POST /api/alerts/acknowledgments", Gate.NONE, null, null, false),
        new Area("Alert history (doors, motion, who silenced what)", "GET /api/alerts/history", null, Gate.NONE, null, null, false),
        new Area("Devices: inventory, status and commands", "GET /api/devices", "POST /api/devices/x/command", Gate.NONE, null, null, false),
        new Area("Device type re-detect", null, "POST /api/devices/redetect/preview", Gate.ADMIN, "api/DeviceRedetectController", "Only an administrator can re-detect device types", false),
        new Area("Rules and workflows", "GET /api/rules/workflows", "POST /api/rules/workflows", Gate.NONE, null, null, false),
        new Area("Knowledge notes behind Ask", "GET /api/kb/nodes", "POST /api/kb/curate", Gate.NONE, null, null, false),
        new Area("Ask: explain and diagnose", "POST /api/helpdesk/ask", null, Gate.NONE, null, null, true),
        new Area("Ask: action proposals", "POST /api/helpdesk/propose", null, Gate.ADMIN, "api/HelpdeskProposeController", "Action proposals are administrator only.", true),
        new Area("Places (locations)", "GET /api/locations", "POST /api/locations", Gate.NONE, null, null, false),
        new Area("Family Hub notes, chores and profiles", "GET /api/notes", "POST /api/notes", Gate.NONE, null, null, false),
        new Area("Cameras", "GET /api/camera/list", "POST /api/camera/x/liveview/start", Gate.NONE, null, null, false),
        new Area("Presence and arming", "GET /api/presence", "PUT /api/presence", Gate.NONE, null, null, false),
        new Area("Presence history", "GET /api/presence/activity", null, Gate.ADMIN_OR_ADULT, "api/PresenceActivityController", "This role cannot view presence history", false),
        new Area("Historical readings", "GET /api/events/telemetry-history", null, Gate.NONE, null, null, false),
        new Area("Event history", "GET /api/events", null, Gate.NONE, null, null, false),
        new Area("Dashboard settings", "GET /api/dashboard/config", null, Gate.NONE, null, null, false),
        new Area("Cabin status summary", "GET /api/cross-domain/cabin-status-summary", null, Gate.ADMIN_OR_ADULT, "api/CrossDomainController", "This role cannot view cabin status", false),
        new Area("Assistant inventory", "GET /api/assistant/inventory", null, Gate.ADMIN_OR_ADULT, "api/AssistantInventoryController", "The assistant inventory needs an administrator or adult household member.", false),
        new Area("Platform import: records", "GET /api/platform-import/records", null, Gate.ADMIN_OR_ADULT, "api/PlatformImportController", "This role cannot view import records", false),
        new Area("Platform import: confirm", null, "POST /api/platform-import/x/confirm", Gate.ADMIN, "api/PlatformImportController", "This role cannot manage platform imports", false),
        new Area("Guest Access links", "GET /api/access-tokens", "POST /api/access-tokens", Gate.ADMIN, "security/CabinAccessTokensController", ADMIN_MARKER, false),
        new Area("Managed users", "GET /api/managed-users", "POST /api/managed-users", Gate.ADMIN, "security/ManagedUsersController", ADMIN_MARKER, false),
        new Area("Location roles", "GET /api/presence/roles", "PUT /api/presence/roles", Gate.ADMIN, "api/PresenceRolesController", "Only an administrator can assign location roles", false),
        new Area("Disk status", "GET /api/system/storage", null, Gate.NONE, null, null, false),
        new Area("Trim old backups", null, "POST /api/system/storage/trim", Gate.ADMIN, "api/StorageTrimController", "Only an administrator can trim storage", false),
        new Area("Platform info", "GET /api/system/platform-info", null, Gate.ADMIN, "api/SystemController", "This role cannot view platform info", false),
        new Area("Optimization Opportunities", "GET /api/opportunities", "PATCH /api/opportunities/x/status", Gate.ADMIN, "api/OpportunitiesController", "This role cannot view or manage optimization opportunities", false));

    static final List<String> WHO = List.of("Not signed in", "Administrator", "Google household member", "Managed household member", "Managed viewer", "Guest link", "Demo link");

    private GoogleAuthInterceptor interceptor;
    private CabinSessionService cabinSessions;
    private ManagedUserService managedUsers;
    private CabinAccessTokenService accessTokens;
    private final List<String> sentUrls = new ArrayList<>();

    @BeforeEach
    void setUp() {
        sentUrls.clear();
        GoogleAuthInterceptorTest fixtures = new GoogleAuthInterceptorTest();
        accessTokens = new CabinAccessTokenService(fixtures.new InMemoryAccessTokenStore());
        managedUsers = new ManagedUserService(new GoogleAuthInterceptorTest.InMemoryManagedUserStore(), new GoogleAuthInterceptorTest.InMemoryMagicLinkTokenStore(),
            new GoogleAuthInterceptorTest.InMemoryManagedUserSessionStore(), (to, name, url) -> sentUrls.add(url));
        ReflectionTestUtils.setField(managedUsers, "frontendOrigin", "http://localhost:5173");
        cabinSessions = new CabinSessionService(new GoogleAuthInterceptorTest.InMemoryCabinSessionStore());
        interceptor = new GoogleAuthInterceptor(accessTokens, managedUsers, cabinSessions);
        ReflectionTestUtils.setField(interceptor, "adminEmailsRaw", "admin@example.com");
    }

    private String managedToken(String email, ManagedUserRole role) {
        ManagedUser user = managedUsers.create(email, "Test", role, "admin@example.com");
        managedUsers.invite(user.id());
        String url = sentUrls.get(sentUrls.size() - 1);
        return managedUsers.consumeMagicLink(url.substring(url.lastIndexOf('/') + 1)).orElseThrow().token();
    }

    /** True when the request got past the interceptor and, for a role-gated handler, past the handler's gate. */
    private boolean allowed(String who, String methodPath, Gate gate) throws Exception {
        String[] parts = methodPath.split(" ", 2);
        String method = parts[0];
        String path = parts[1];
        if (who.equals("Demo link")) {
            // a demo link carries no household role, so a role-gated handler refuses it
            return gate == Gate.NONE && "GET".equals(method) && DemoAccessPolicy.classify(path) != DemoAccessPolicy.RouteClass.DENY;
        }
        // WebConfig registers the interceptor on a fixed list of path patterns; a route outside it never meets the interceptor.
        // (Its handler's own role gate, if any, still applies, but a handler gate needs a role, which only the interceptor sets.)
        if (!interceptedByWebConfig(path)) return gate == Gate.NONE;
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        switch (who) {
            case "Not signed in" -> { }
            case "Administrator" -> request.addHeader("Authorization", "CabinSession " + cabinSessions.issue("admin@example.com").token());
            case "Google household member" -> request.addHeader("Authorization", "CabinSession " + cabinSessions.issue("member@example.com").token());
            case "Managed household member" -> request.addHeader("Authorization", "ManagedSession " + managedToken("mm@example.com", ManagedUserRole.HOUSEHOLD_MEMBER));
            case "Managed viewer" -> request.addHeader("Authorization", "ManagedSession " + managedToken("mv@example.com", ManagedUserRole.VIEWER));
            case "Guest link" -> request.setParameter("t", accessTokens.create("g", List.of("dashboard", "device_states", "alerts_read", "observations_read"), null, "admin@example.com").token());
            default -> throw new IllegalArgumentException(who);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        if (!interceptor.preHandle(request, response, new Object())) return false;
        Object role = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE);
        return switch (gate) {
            case NONE -> true;
            case ADMIN -> role == HouseholdRole.ADMINISTRATOR;
            case ADMIN_OR_ADULT -> role == HouseholdRole.ADMINISTRATOR || role == HouseholdRole.ADULT_HOUSEHOLD_MEMBER;
        };
    }

    private static List<String> interceptorPatterns() throws IOException {
        String src = Files.readString(BACKEND_SRC.resolve("security/WebConfig.java"));
        int from = src.indexOf(".addPathPatterns(");
        assertThat(from).as("WebConfig.addPathPatterns").isGreaterThan(0);
        String list = src.substring(from, src.indexOf(");", from));
        List<String> patterns = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"(/[^\"]+)\"").matcher(list);
        while (m.find()) patterns.add(m.group(1));
        return patterns;
    }

    private boolean interceptedByWebConfig(String path) throws IOException {
        org.springframework.util.AntPathMatcher matcher = new org.springframework.util.AntPathMatcher();
        for (String pattern : interceptorPatterns()) if (matcher.match(pattern, path)) return true;
        return false;
    }

    private String cell(Area area, String who) throws Exception {
        boolean read = area.read() != null && allowed(who, area.read(), area.gate());
        boolean change = area.change() != null && allowed(who, area.change(), area.gate());
        String letters;
        if (area.use()) letters = read ? "U" : "-";
        else letters = (read ? "R" : "") + (change ? "W" : "");
        if (letters.isEmpty()) letters = "-";
        if (who.equals("Guest link") && !letters.equals("-") && !letters.equals(cell(area, "Not signed in"))) return letters + "*";
        return letters;
    }

    private Map<String, List<String>> computed() throws Exception {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Area area : AREAS) {
            List<String> cells = new ArrayList<>();
            for (String who : WHO) cells.add(cell(area, who));
            out.put(area.name(), cells);
        }
        return out;
    }

    private static String render(Map<String, List<String>> matrix) {
        StringBuilder sb = new StringBuilder("| Area | " + String.join(" | ", WHO) + " |\n|---|" + "---|".repeat(WHO.size()) + "\n");
        matrix.forEach((area, cells) -> sb.append("| ").append(area).append(" | ").append(String.join(" | ", cells)).append(" |\n"));
        return sb.toString();
    }

    private static final Path BACKEND_SRC = Path.of("src/main/java/com/cabin/orchestrator");
    private static final Path GUIDE = Path.of("../../docs/ai-assistant/user-guide/access-and-storage.md").toAbsolutePath().normalize();

    @Test
    void theGuideTableMatchesWhatTheCodeDoes() throws Exception {
        assumeTrue(Files.isRegularFile(GUIDE), "docs not reachable");
        Map<String, List<String>> expected = computed();
        String guide = Files.readString(GUIDE).replace("\r\n", "\n");
        Map<String, List<String>> documented = new LinkedHashMap<>();
        boolean in = false;
        for (String line : guide.split("\n")) {
            if (line.startsWith("| Area |")) { in = true; continue; }
            if (in && line.startsWith("|---")) continue;
            if (in && line.startsWith("|")) {
                String[] cells = line.substring(1, line.length() - 1).split("\\|", -1);
                List<String> row = new ArrayList<>();
                for (int i = 1; i < cells.length; i++) row.add(cells[i].strip());
                documented.put(cells[0].strip(), row);
            } else if (in) break;
        }
        assertThat(documented).as("the role-by-area table in access-and-storage.md; the computed table is:\n" + render(expected)).isNotEmpty();
        assertThat(documented.keySet()).as("areas in the guide table").containsExactlyElementsOf(expected.keySet());
        List<String> differences = new ArrayList<>();
        expected.forEach((area, cells) -> {
            if (!cells.equals(documented.get(area))) differences.add(area + ": code " + cells + " guide " + documented.get(area));
        });
        assertThat(differences).as("rows where the guide disagrees with the code").isEmpty();
    }

    @Test
    void everyRoleGateIsStillInItsControllerAndEveryAdminAwareControllerHasARow() throws IOException {
        List<String> missingMarkers = new ArrayList<>();
        for (Area a : AREAS) {
            if (a.controller() == null) continue;
            String source = Files.readString(BACKEND_SRC.resolve(a.controller() + ".java"));
            String marker = a.marker();
            if (marker.equals(ADMIN_MARKER)) {
                if (!source.contains("AdministratorOnly.require")) missingMarkers.add(a.name() + ": no AdministratorOnly.require in " + a.controller());
            } else if (!source.contains(marker)) {
                missingMarkers.add(a.name() + ": '" + marker + "' not in " + a.controller());
            }
        }
        assertThat(missingMarkers).as("a role gate this table relies on is no longer in the controller").isEmpty();

        List<String> named = AREAS.stream().map(Area::controller).filter(c -> c != null).map(c -> c + ".java").toList();
        List<String> unnamed = new ArrayList<>();
        try (var files = Files.walk(BACKEND_SRC)) {
            files.filter(f -> f.toString().endsWith("Controller.java")).forEach(f -> {
                try {
                    String src = Files.readString(f);
                    boolean gated = src.contains("HouseholdRole.ADMINISTRATOR") || src.contains("AdministratorOnly.require") || src.contains("AssistantInventoryService.mayRead");
                    String rel = BACKEND_SRC.relativize(f).toString().replace('\\', '/');
                    if (gated && !named.contains(rel)) unnamed.add(rel);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(unnamed).as("controllers that check the administrator role but have no row in the role table").isEmpty();
    }
}
