package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceRepository;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.JdbcKnowledgeNodeRepository;
import com.cabin.orchestrator.devices.KbGeneratorService;
import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Wiring only -- KbGeneratorService's own content logic is covered in KbGeneratorServiceTest. */
@Testcontainers
class KnowledgeNodeControllerTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private KnowledgeNodeController newController(DeviceRegistry registry) {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        KnowledgeNodeRepository knowledgeNodeRepository = new JdbcKnowledgeNodeRepository(jdbc);
        DeviceRepository noOpDeviceRepository = new DeviceRepository() {
            @Override public void upsert(String deviceId, DeviceMetadata metadata) { }
            @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.empty(); }
            @Override public Map<String, DeviceMetadata> loadAll() { return Map.of(); }
        };
        DeviceReportingRelationshipRepository noOpReportingRepository = new DeviceReportingRelationshipRepository() {
            @Override public void upsert(DeviceReportingRelationship relationship) { }
            @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) { return List.of(); }
            @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.of(); }
        };
        KbGeneratorService generator = new KbGeneratorService(registry, noOpDeviceRepository, noOpReportingRepository, knowledgeNodeRepository);
        return new KnowledgeNodeController(generator, knowledgeNodeRepository);
    }

    private static MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/kb/nodes");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }

    @Test
    void regenerateWritesKnowledgeNodesForEveryInScopeDevice() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-controller_test", "Controller test device", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic", true, "cabin"), Map.of());
        registry.applyLifecycleAction("z2m-controller_test", DeviceLifecycleAction.ACCEPT);
        KnowledgeNodeController controller = newController(registry);

        Map<String, Integer> result = controller.regenerate();

        assertTrue(result.get("chunksWritten") >= 1);
        assertFalse(controller.nodesFor("z2m-controller_test", requestWithRole(null)).isEmpty());
    }

    @Test
    void curateAlwaysForcesManuallyCuratedRegardlessOfRequestBody() {
        KnowledgeNodeController controller = newController(new DeviceRegistry(List.of()));

        KnowledgeNode result = controller.curate(Map.of(
            "entityRef", "procedure-water-shutoff", "chunkType", "troubleshooting",
            "content", "1. Locate the shutoff valve in the mech room. 2. Turn clockwise until it stops.",
            "source", "auto_generated" // deliberately trying to smuggle the wrong source through
        ));

        assertEquals(KnowledgeSource.MANUALLY_CURATED, result.source(),
            "the endpoint must force this regardless of what the request body claims");
        assertEquals(1, controller.nodesFor("procedure-water-shutoff", requestWithRole(null)).size());
    }

    @Test
    void regenerateNeverOverwritesACuratedDescriptionForTheSameDevice() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-curated_controller_test", "Curated controller test", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic", true, "cabin"), Map.of());
        registry.applyLifecycleAction("z2m-curated_controller_test", DeviceLifecycleAction.ACCEPT);
        KnowledgeNodeController controller = newController(registry);
        controller.curate(Map.of("entityRef", "z2m-curated_controller_test", "chunkType", "description",
            "content", "Hand-written, more accurate than the auto-generated version."));

        controller.regenerate();

        KnowledgeNode found = controller.nodesFor("z2m-curated_controller_test", requestWithRole(null)).stream()
            .filter(n -> n.chunkType().name().equals("DESCRIPTION")).findFirst().orElseThrow();
        assertEquals(KnowledgeSource.MANUALLY_CURATED, found.source());
        assertEquals("Hand-written, more accurate than the auto-generated version.", found.content());
    }

    @Test
    void regenerateOneOnlyTouchesTheNamedDevice() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-single_target", "Single target device", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic", true, "cabin"), Map.of());
        registry.applyLifecycleAction("z2m-single_target", DeviceLifecycleAction.ACCEPT);
        KnowledgeNodeController controller = newController(registry);

        controller.regenerateOne("z2m-single_target");

        assertFalse(controller.nodesFor("z2m-single_target", requestWithRole(null)).isEmpty());
    }

    @Test
    void nodesRedactsCredentialPointerContentForANonAdministratorCaller() {
        KnowledgeNodeController controller = newController(new DeviceRegistry(List.of()));
        controller.curate(Map.of("entityRef", "Resend", "chunkType", "credential_pointer",
            "content", "vault_resend_api_key"));

        List<KnowledgeNode> asAdmin = controller.nodes(requestWithRole(HouseholdRole.ADMINISTRATOR));
        List<KnowledgeNode> asNonAdmin = controller.nodes(requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));
        List<KnowledgeNode> asAnonymous = controller.nodes(requestWithRole(null));

        KnowledgeNode adminView = asAdmin.stream().filter(n -> n.entityRef().equals("Resend")).findFirst().orElseThrow();
        KnowledgeNode nonAdminView = asNonAdmin.stream().filter(n -> n.entityRef().equals("Resend")).findFirst().orElseThrow();
        KnowledgeNode anonymousView = asAnonymous.stream().filter(n -> n.entityRef().equals("Resend")).findFirst().orElseThrow();

        assertTrue(adminView.content().contains("vault_resend_api_key"), "an administrator sees the real vault entry name");
        assertFalse(nonAdminView.content().contains("vault_"), "a non-administrator must never see it, even via the raw listing endpoint");
        assertFalse(anonymousView.content().contains("vault_"), "an unauthenticated caller (this route stays open per WebConfig) must fail closed too");
    }

    @Test
    void nodesForAlsoRedactsCredentialPointerContentForANonAdministratorCaller() {
        KnowledgeNodeController controller = newController(new DeviceRegistry(List.of()));
        controller.curate(Map.of("entityRef", "Blink Cloud Account", "chunkType", "credential_pointer",
            "content", "vault_blink_username, vault_blink_password"));

        KnowledgeNode nonAdminView = controller.nodesFor("Blink Cloud Account", requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).get(0);

        assertEquals("Contact an administrator for this credential.", nonAdminView.content());
    }
}
