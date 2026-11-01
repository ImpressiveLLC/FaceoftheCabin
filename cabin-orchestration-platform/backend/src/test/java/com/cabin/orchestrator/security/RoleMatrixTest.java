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
 * The role-by-area table in docs/ai-assistant/user-guide/access-and-storage.md ("What each role can do, by area") is checked against
 * the code, so the guide and the policy cannot drift (W-137; same family as WriteGateAuditTest and AdministratorRouteAuditTest).
 * A change to who may do what has to change that table in the same pull request.
 *
 * <p>Each area names one representative route per operation. Whether a way of getting in may reach it is computed by running the REAL
 * GoogleAuthInterceptor with a real credential of that kind (an administrator and an other-Google CabinSession, a managed household
 * member and a managed viewer ManagedSession, a guest link with every scope ticked), then applying a MODEL of the handler's own role
 * gate (the area's Gate). The model is tied to the controller only by requiring the gate's refusal message to still be in the
 * controller source: this test does not call the controllers, so a controller whose check changes while its message stays the same is
 * not caught here. The demo link is classified by DemoAccessPolicy. Cell letters: C create, R read, U update, D delete (from the
 * operation each probe stands for), + an action that is none of those (ask, send a device command, fire a workflow, trim backups), - none.
 * A controller that checks the administrator role and is not named by any area fails the completeness test: add a row.
 */
class RoleMatrixTest {

    enum Gate { NONE, ADMIN, ADMIN_OR_ADULT }

    /** One probe: the letter of the operation it stands for (C, R, U, D, or + for an action) and "METHOD /path". */
    record Op(String letter, String route) {}

    record Area(String name, Gate gate, String controller, String marker, List<Op> ops) {}

    private static Op op(String letter, String route) { return new Op(letter, route); }

    static final String ADMIN_MARKER = AdministratorOnly.MESSAGE;

    static final List<Area> AREAS = List.of(
        new Area("Status checks and alerts", Gate.NONE, null, null, List.of(op("R", "GET /api/alerts/active"), op("C", "POST /api/alerts/acknowledgments"), op("D", "DELETE /api/alerts/acknowledgments/x"))),
        new Area("Alert history (doors, motion, who silenced what)", Gate.NONE, null, null, List.of(op("R", "GET /api/alerts/history"))),
        new Area("Devices: inventory, status and commands", Gate.NONE, null, null, List.of(op("R", "GET /api/devices"), op("C", "POST /api/devices"), op("U", "PUT /api/devices/x"), op("D", "DELETE /api/devices/x"), op("+", "POST /api/devices/x/command"))),
        new Area("Device type re-detect", Gate.ADMIN, "api/DeviceRedetectController", "Only an administrator can re-detect device types", List.of(op("+", "POST /api/devices/redetect/preview"))),
        new Area("Home Assistant switch calls", Gate.NONE, null, null, List.of(op("+", "POST /api/ha/services"))),
        new Area("Rules and workflows", Gate.NONE, null, null, List.of(op("R", "GET /api/rules/workflows"), op("C", "POST /api/rules/workflows"), op("D", "DELETE /api/rules/workflows/x"), op("+", "POST /api/rules/workflows/x/fire"))),
        new Area("Schedules", Gate.NONE, null, null, List.of(op("R", "GET /api/schedule/rules"), op("C", "POST /api/schedule/rules"))),
        new Area("Knowledge notes behind Ask", Gate.NONE, null, null, List.of(op("R", "GET /api/kb/nodes"), op("C", "POST /api/kb/curate"))),
        new Area("Ask: explain and diagnose", Gate.NONE, null, null, List.of(op("+", "POST /api/helpdesk/ask"))),
        new Area("Ask: action proposals (off by default)", Gate.ADMIN, "api/HelpdeskProposeController", "Action proposals are administrator only.", List.of(op("+", "POST /api/helpdesk/propose"))),
        new Area("Places (locations)", Gate.NONE, null, null, List.of(op("R", "GET /api/locations"), op("C", "POST /api/locations"), op("U", "PATCH /api/locations/x"), op("D", "DELETE /api/locations/x"))),
        new Area("Family Hub notes, chores and profiles", Gate.NONE, null, null, List.of(op("R", "GET /api/notes"), op("C", "POST /api/notes"), op("U", "PATCH /api/profiles/x"), op("D", "DELETE /api/profiles/x"))),
        new Area("Cameras", Gate.NONE, null, null, List.of(op("R", "GET /api/camera/list"), op("+", "POST /api/camera/x/liveview/start"))),
        new Area("Presence and armed state (change = manual presence override)", Gate.NONE, null, null, List.of(op("R", "GET /api/presence"), op("U", "PUT /api/presence"))),
        new Area("Presence history", Gate.ADMIN_OR_ADULT, "api/PresenceActivityController", "This role cannot view presence history", List.of(op("R", "GET /api/presence/activity"))),
        new Area("Historical readings", Gate.NONE, null, null, List.of(op("R", "GET /api/events/telemetry-history"))),
        new Area("Event history", Gate.NONE, null, null, List.of(op("R", "GET /api/events"))),
        new Area("Dashboard settings", Gate.NONE, null, null, List.of(op("R", "GET /api/dashboard/config"))),
        new Area("Cabin status summary", Gate.ADMIN_OR_ADULT, "api/CrossDomainController", "This role cannot view cabin status", List.of(op("R", "GET /api/cross-domain/cabin-status-summary"))),
        new Area("Assistant inventory", Gate.ADMIN_OR_ADULT, "api/AssistantInventoryController", "The assistant inventory needs an administrator or adult household member.", List.of(op("R", "GET /api/assistant/inventory"))),
        new Area("Tech ID findings (Opportunity Map data)", Gate.NONE, null, null, List.of(op("R", "GET /api/tech-id/findings"), op("U", "PATCH /api/tech-id/findings/x"), op("+", "POST /api/tech-id/findings/x/actions"))),
        new Area("Platform import: records", Gate.ADMIN_OR_ADULT, "api/PlatformImportController", "This role cannot view import records", List.of(op("R", "GET /api/platform-import/records"))),
        new Area("Platform import: confirm", Gate.ADMIN, "api/PlatformImportController", "This role cannot manage platform imports", List.of(op("+", "POST /api/platform-import/x/confirm"))),
        new Area("Guest Access links", Gate.ADMIN, "security/CabinAccessTokensController", ADMIN_MARKER, List.of(op("R", "GET /api/access-tokens"), op("C", "POST /api/access-tokens"), op("D", "DELETE /api/access-tokens/x"))),
        new Area("Roof Monitoring page: entries", Gate.NONE, null, null, List.of(op("R", "GET /api/roof-spec/entries"), op("C", "POST /api/roof-spec/entries"), op("D", "DELETE /api/roof-spec/entries/x"))),
        new Area("Managed users", Gate.ADMIN, "security/ManagedUsersController", ADMIN_MARKER, List.of(op("R", "GET /api/managed-users"), op("C", "POST /api/managed-users"), op("U", "POST /api/managed-users/x/deactivate"))),
        new Area("Location roles", Gate.ADMIN, "api/PresenceRolesController", "Only an administrator can assign location roles", List.of(op("R", "GET /api/presence/roles"), op("U", "PUT /api/presence/roles"))),
        new Area("Disk status", Gate.NONE, null, null, List.of(op("R", "GET /api/system/storage"))),
        new Area("Trim old backups", Gate.ADMIN, "api/StorageTrimController", "Only an administrator can trim storage", List.of(op("R", "GET /api/system/storage/trim"), op("+", "POST /api/system/storage/trim"))),
        new Area("Platform info", Gate.ADMIN, "api/SystemController", "This role cannot view platform info", List.of(op("R", "GET /api/system/platform-info"))),
        new Area("Optimization Opportunities", Gate.ADMIN, "api/OpportunitiesController", "This role cannot view or manage optimization opportunities", List.of(op("R", "GET /api/opportunities"), op("U", "PATCH /api/opportunities/x/status"))));

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
        StringBuilder letters = new StringBuilder();
        for (String letter : List.of("C", "R", "U", "D", "+")) {
            for (Op probe : area.ops()) {
                if (probe.letter().equals(letter) && allowed(who, probe.route(), area.gate())) { letters.append(letter); break; }
            }
        }
        return letters.length() == 0 ? "-" : letters.toString();
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
