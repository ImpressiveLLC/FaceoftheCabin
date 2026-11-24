package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.api.DeviceCatalogShadowController;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.integrations.cameras.BlinkLiveviewService;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantDiscoveryService;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.mqtt.FrigateEventReconciliationService;
import com.cabin.orchestrator.mqtt.MqttBridgeService;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Catalog S3b runtime wiring: the three classifier sites record a rule observation; device reads and the shadow report
 * keep inference parity apart from assigned-type drift; failures never block discovery. Synthetic inputs, no host.
 */
class RuleShadowRuntimeTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static DeviceCatalogService shipped() {
        return new DeviceCatalogService("/nonexistent/device-catalog.json", RuleShadowRuntimeTest.class.getClassLoader());
    }

    /** A copy of the shipped catalog with CHANGE applied to its match rules, loaded from a mount path. */
    private static DeviceCatalogService withRules(Path dir, java.util.function.Consumer<ArrayNode> change) throws Exception {
        ObjectNode catalog;
        try (InputStream in = RuleShadowRuntimeTest.class.getClassLoader().getResourceAsStream(DeviceCatalogService.CLASSPATH_COPY)) {
            catalog = (ObjectNode) JSON.readTree(in);
        }
        change.accept((ArrayNode) catalog.get("match_rules"));
        Path mount = dir.resolve("device-catalog.json");
        Files.writeString(mount, JSON.writeValueAsString(catalog));
        DeviceCatalogService service = new DeviceCatalogService(mount.toString(), RuleShadowRuntimeTest.class.getClassLoader());
        assertEquals("mount", service.source());
        return service;
    }

    private static ObjectNode rule(ArrayNode rules, String id) {
        for (var r : rules) if (r.get("id").asText().equals(id)) return (ObjectNode) r;
        throw new IllegalArgumentException(id);
    }

    private static HomeAssistantDiscoveryService ha(DeviceRegistry registry, HomeAssistantAdapter adapter, DeviceCatalogService catalog) {
        when(adapter.normalizedState(anyString())).thenReturn("ONLINE");
        HomeAssistantDiscoveryService service = new HomeAssistantDiscoveryService(adapter, registry,
            new PresenceSignalRegistry(), mock(PresenceService.class), mock(EventPublisher.class));
        service.setCatalogService(catalog);
        return service;
    }

    @Test
    void homeAssistantDiscoveryRecordsAnIndependentObservationAndAMatch() {
        DeviceCatalogService catalog = shipped();
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.setCatalogService(catalog);
        HomeAssistantAdapter adapter = mock(HomeAssistantAdapter.class);
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("lock.front_door", "locked", Map.of("friendly_name", "Front door")),
            new HomeAssistantAdapter.DiscoveredEntity("sensor.kidde_co_level", "0", Map.of("friendly_name", "Kidde CO Level"))));
        ha(registry, adapter, catalog).discover();

        Map<String, Object> lock = registry.get("ha-cabin-lock-front-door").attributes();
        assertEquals("EVALUATED", lock.get("ruleEvaluation"));
        assertEquals("ha-01-lock", lock.get("catalogRuleId"));
        assertEquals("LOCK", lock.get("catalogRuleType"));
        assertEquals("INDEPENDENT", lock.get("ruleIndependence"));
        assertEquals("MATCH", lock.get("ruleInferenceComparison"));
        assertEquals("MATCH", lock.get("assignedTypeComparison"));
        assertEquals(64, ((String) lock.get("ruleInputSha256")).length());
        assertFalse(lock.values().stream().anyMatch(v -> String.valueOf(v).contains("ruleInput:")), "no raw input stored");

        Map<String, Object> co = registry.get("ha-cabin-sensor-kidde-co-level").attributes();
        assertEquals("ha-15-name-co-level", co.get("catalogRuleId"));
        assertEquals(true, co.get("ruleWorkaround"));
    }

    @Test
    void zigbeeBridgeDeviceListRecordsAnObservation() throws Exception {
        DeviceCatalogService catalog = shipped();
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.setCatalogService(catalog);
        Zigbee2MqttAdapter adapter = new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry());
        adapter.setCatalogService(catalog);
        adapter.messageArrived("zigbee2mqtt/bridge/devices", new MqttMessage("""
            [{"friendly_name":"leak_sink","ieee_address":"0x00124b0000000001","type":"EndDevice","definition":{
              "model":"SNZB-05P","description":"Water leak sensor","vendor":"SONOFF","exposes":[]}}]
            """.getBytes()));
        String id = registry.all().stream().map(DeviceStatus::deviceId).filter(d -> d.contains("leak_sink")).findFirst().orElseThrow();
        Map<String, Object> attrs = registry.get(id).attributes();
        assertEquals("zb-01-snzb-05-or-water-leak", attrs.get("catalogRuleId"));
        assertEquals("MATCH", attrs.get("ruleInferenceComparison"));
    }

    private static MqttBridgeService mqtt(DeviceRegistry registry, DeviceCatalogService catalog) {
        MqttBridgeService bridge = new MqttBridgeService(registry, mock(EventPublisher.class), mock(PresenceService.class),
            new PresenceSignalRegistry(), new SecurityStateRegistry(), mock(FrigateEventReconciliationService.class),
            mock(BlinkLiveviewService.class));
        bridge.setCatalogService(catalog);
        return bridge;
    }

    @Test
    void anExistingMqttDeviceIsObservedOnEachMessageWithoutTouchingItsTypeOrRegistration() throws Exception {
        DeviceCatalogService catalog = shipped();
        DeviceRegistry registry = spy(new DeviceRegistry(List.of()));
        registry.setCatalogService(catalog);
        registry.register(new DeviceStatus("cabin-gate-lock", DeviceType.LOCK, "gate", "ONLINE", Instant.now(), Map.of("locked", true), "cabin"));
        clearInvocations(registry);

        mqtt(registry, catalog).messageArrived("cabin/device/cabin-gate-lock/state", new MqttMessage("{\"temp_f\": 70}".getBytes()));

        verify(registry, never()).registerCandidate(any(DeviceDescriptor.class), any());
        DeviceStatus after = registry.get("cabin-gate-lock");
        assertEquals(DeviceType.LOCK, after.type(), "the persisted type is untouched");
        assertEquals("mb-02-temp-f", after.attributes().get("catalogRuleId"));
        assertEquals("MATCH", after.attributes().get("ruleInferenceComparison"), "inference parity on the same keys");
        assertEquals("ASSIGNED_TYPE_DIFFERS", after.attributes().get("assignedTypeComparison"), "drift is an observation");
    }

    @Test
    void aNewMqttDeviceIsObservedToo() throws Exception {
        DeviceCatalogService catalog = shipped();
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.setCatalogService(catalog);
        mqtt(registry, catalog).messageArrived("cabin/device/well-pump/state", new MqttMessage("{\"psi\": 51}".getBytes()));
        assertEquals("mb-01-psi", registry.get("well-pump").attributes().get("catalogRuleId"));
    }

    @Test
    void aRuleThatDisagreesWithTheClassifierIsReportedAsInferenceParityNotDrift(@TempDir Path dir) throws Exception {
        DeviceCatalogService catalog = withRules(dir, rules -> rule(rules, "ha-01-lock").put("returns", "CAMERA"));
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.setCatalogService(catalog);
        HomeAssistantAdapter adapter = mock(HomeAssistantAdapter.class);
        when(adapter.discover("cabin")).thenReturn(List.of(new HomeAssistantAdapter.DiscoveredEntity("lock.front_door", "locked", Map.of())));
        ha(registry, adapter, catalog).discover();
        Map<String, Object> attrs = registry.get("ha-cabin-lock-front-door").attributes();
        assertEquals(DeviceType.LOCK, registry.get("ha-cabin-lock-front-door").type());
        assertEquals("RULE_INFERENCE_DIFFERS", attrs.get("ruleInferenceComparison"));
        assertEquals("ASSIGNED_TYPE_DIFFERS", attrs.get("assignedTypeComparison"));

        @SuppressWarnings("unchecked")
        Map<String, Object> rules = (Map<String, Object>) new DeviceCatalogShadowController(registry, catalog).report().get("ruleShadow");
        assertEquals(1, rules.get("ruleInferenceDiffers"));
        assertEquals(1, rules.get("assignedTypeDiffers"));
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) rules.get("counts");
        assertEquals(1, counts.get("evaluated"));
        assertEquals(1, counts.get("independent"));
        assertEquals(0, counts.get("delegated"));
        assertEquals(registry.all().size() - 1, counts.get("notEvaluated"), "devices never classified since startup");
    }

    @Test
    void anUnusableRuleSetIsReportedAndNeverBlocksDiscovery(@TempDir Path dir) throws Exception {
        DeviceCatalogService catalog = withRules(dir, rules -> rule(rules, "ha-04-smoke").remove("when"));
        assertNotNull(catalog.evaluatorProblem());
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.setCatalogService(catalog);
        HomeAssistantAdapter adapter = mock(HomeAssistantAdapter.class);
        when(adapter.discover("cabin")).thenReturn(List.of(new HomeAssistantAdapter.DiscoveredEntity("lock.front_door", "locked", Map.of())));
        ha(registry, adapter, catalog).discover();
        DeviceStatus lock = registry.get("ha-cabin-lock-front-door");
        assertNotNull(lock, "discovery still registered the device");
        assertEquals(DeviceType.LOCK, lock.type());
        assertEquals("RULE_EVALUATION_UNAVAILABLE", lock.attributes().get("ruleEvaluation"));
        @SuppressWarnings("unchecked")
        Map<String, Object> rules = (Map<String, Object>) new DeviceCatalogShadowController(registry, catalog).report().get("ruleShadow");
        assertTrue(String.valueOf(rules.get("evaluator")).startsWith("unavailable"));
    }

    @Test
    void anEvaluationFailureIsRecordedNotThrown() {
        DeviceCatalogService catalog = shipped();
        CatalogRuleEvaluator.SiteInput broken = new CatalogRuleEvaluator.SiteInput() {
            public String site() { return "home_assistant"; }
            public String field(String name) { throw new IllegalStateException("boom"); }
        };
        assertDoesNotThrow(() -> catalog.observe("dev", broken, DeviceType.LOCK, Map.of("domain", "x")));
        assertEquals("RULE_EVALUATION_UNAVAILABLE", catalog.observation("dev").orElseThrow().status());
    }

    @Test
    void aDeviceNeverClassifiedSinceStartupIsNotEvaluated() {
        DeviceCatalogService catalog = shipped();
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.setCatalogService(catalog);
        registry.register(new DeviceStatus("manual-1", DeviceType.CAMERA, "cam", "ONLINE", Instant.now(), Map.of(), "cabin"));
        Map<String, Object> attrs = registry.get("manual-1").attributes();
        assertEquals("NOT_EVALUATED", attrs.get("ruleEvaluation"));
        assertFalse(attrs.containsKey("catalogRuleId"));
    }
}
