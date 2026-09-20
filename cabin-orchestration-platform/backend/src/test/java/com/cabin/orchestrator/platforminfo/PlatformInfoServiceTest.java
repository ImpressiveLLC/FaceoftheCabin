package com.cabin.orchestrator.platforminfo;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.helpdesk.OllamaClient;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug #5 -- covers the three real success criteria: versions degrade
 * gracefully instead of throwing when an integration is unreachable/not yet
 * configured, the hardware catalog has exactly the 6 rows asked for and
 * never fabricates a device that isn't actually paired (Refrigeration),
 * and the AI disclosure names the real model/hosting/network facts.
 */
class PlatformInfoServiceTest {

    private static ObjectProvider<BuildProperties> emptyBuildProperties() {
        return new ObjectProvider<>() {
            @Override public BuildProperties getObject() { throw new NoSuchBeanDefinitionException(BuildProperties.class); }
            @Override public BuildProperties getObject(Object... args) { throw new NoSuchBeanDefinitionException(BuildProperties.class); }
            @Override public BuildProperties getIfAvailable() { return null; }
            @Override public BuildProperties getIfUnique() { return null; }
        };
    }

    private PlatformInfoService newService(OllamaClient ollama) {
        HomeAssistantAdapter ha = new HomeAssistantAdapter();
        ReflectionTestUtils.setField(ha, "cabinHaToken", "");
        ReflectionTestUtils.setField(ha, "homeHaToken", "");
        DeviceRegistry registry = new DeviceRegistry(java.util.List.of());
        Zigbee2MqttAdapter z2m = new Zigbee2MqttAdapter(registry, new EventPublisher(), new SignalQualityRegistry());
        return new PlatformInfoService(ha, z2m, ollama, emptyBuildProperties());
    }

    @Test
    @SuppressWarnings("unchecked")
    void unreachableIntegrationsDegradeToAnExplanatoryStringNeverAnException() {
        Map<String, Object> result = newService(prompt -> Optional.empty()).get();

        Map<String, Object> versions = (Map<String, Object>) result.get("versions");
        assertTrue(((String) versions.get("homeAssistant")).contains("unavailable"));
        assertTrue(((String) versions.get("zigbee2mqtt")).contains("unavailable"));
        assertTrue(((String) versions.get("ollama")).contains("unavailable"));
        assertTrue(((String) versions.get("cabinBackend")).contains("unknown"));
        assertTrue(((String) versions.get("mqttBroker")).contains("not exposed"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ollamaVersionIsSurfacedWhenTheClientReturnsOne() {
        OllamaClient reachable = new OllamaClient() {
            @Override public Optional<String> generate(String prompt) { return Optional.empty(); }
            @Override public Optional<String> fetchVersion() { return Optional.of("0.3.12"); }
        };

        Map<String, Object> versions = (Map<String, Object>) newService(reachable).get().get("versions");

        assertEquals("0.3.12", versions.get("ollama"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hardwareCatalogHasExactlyTheSixDocumentedRowsAndNeverInventsAFridge() {
        Map<String, Object> result = newService(prompt -> Optional.empty()).get();

        var hardware = (java.util.List<?>) result.get("hardware");
        assertEquals(6, hardware.size());
        String rendered = hardware.toString();
        assertTrue(rendered.contains("Host"));
        assertTrue(rendered.contains("Zigbee coordinator"));
        assertTrue(rendered.contains("Cameras"));
        assertTrue(rendered.contains("Environmental"));
        assertTrue(rendered.contains("Refrigeration"));
        assertTrue(rendered.contains("Sensors"));
        assertTrue(rendered.contains("Liebherr") && rendered.contains("Loonie Mc Frigerton"),
            "corrected 2026-09-04 -- a real Liebherr fridge is paired and reporting multiple services; the row used to wrongly claim none was paired");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aiDisclosureNamesTheRealLocalModelAndTailscaleOnlyExposure() {
        Map<String, Object> disclosure = (Map<String, Object>) newService(prompt -> Optional.empty()).get().get("aiDisclosure");

        assertEquals("llama3.2:3b (Ollama)", disclosure.get("model"));
        assertTrue(((String) disclosure.get("hostedWhere")).contains("M920q"));
        assertTrue(((String) disclosure.get("networkExposure")).contains("Tailscale"));
        assertTrue(((String) disclosure.get("dataHandling")).contains("is ever sent to an external AI service"));
    }

    // 2026-09-20: Config > Platform must list everything versioned (backend, Kafka,
    // Java, Node, Node-RED ...), not just five integrations.

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> specItemsById(Map<String, Object> result) {
        Map<String, Object> specs = (Map<String, Object>) result.get("specs");
        Map<String, Map<String, Object>> byId = new java.util.LinkedHashMap<>();
        for (Map<String, Object> group : (java.util.List<Map<String, Object>>) specs.get("groups")) {
            for (Map<String, Object> item : (java.util.List<Map<String, Object>>) group.get("items")) byId.put((String) item.get("id"), item);
        }
        return byId;
    }

    @Test
    @SuppressWarnings("unchecked")
    void specsListTheBackendKafkaJavaNodeNodeRedAndTheRestOfTheStack() {
        Map<String, Object> result = newService(prompt -> Optional.empty()).get();

        Map<String, Map<String, Object>> items = specItemsById(result);
        for (String id : java.util.List.of("app.cabin-backend", "rt.java", "rt.node", "be.spring-boot", "be.kafka-clients", "svc.kafka",
                "svc.postgres", "svc.node-red", "svc.home-assistant", "svc.zigbee2mqtt", "svc.grafana", "svc.ollama", "host.docker")) {
            assertTrue(items.containsKey(id), "platform specs should list " + id);
        }
        assertEquals("21", items.get("rt.java").get("declared"));
        assertEquals("3.3.5", items.get("be.spring-boot").get("declared"));
        assertEquals("7.6.1", items.get("svc.kafka").get("declared"));
        assertEquals("floating", items.get("svc.node-red").get("track"));
        assertEquals("pinned", items.get("svc.kafka").get("track"));
        Map<String, Object> specs = (Map<String, Object>) result.get("specs");
        Map<String, Integer> counts = (Map<String, Integer>) specs.get("counts");
        assertEquals(items.size(), specs.get("total"));
        assertEquals(items.size(), counts.values().stream().mapToInt(Integer::intValue).sum(), "every entry is counted under exactly one track");
        assertTrue(counts.get("floating") > 0, "the floating tags are what needs maintenance attention, so they must be counted");
    }

    @Test
    void runningVersionsAreFilledInWhereTheBackendCanAskAndNullWhereItCannot() {
        OllamaClient reachable = new OllamaClient() {
            @Override public Optional<String> generate(String prompt) { return Optional.empty(); }
            @Override public Optional<String> fetchVersion() { return Optional.of("0.3.12"); }
        };

        Map<String, Map<String, Object>> items = specItemsById(newService(reachable).get());

        assertEquals(Runtime.version().toString(), items.get("rt.java").get("running"));
        assertEquals(org.springframework.boot.SpringBootVersion.getVersion(), items.get("be.spring-boot").get("running"));
        assertEquals("0.3.12", items.get("svc.ollama").get("running"));
        // Asked and got nothing back: an explicit "not known", never an invented value.
        assertEquals(null, items.get("svc.home-assistant").get("running"));
        assertEquals(true, items.get("svc.home-assistant").get("liveProbe"));
        assertEquals(null, items.get("svc.postgres").get("running"), "no database in this unit test");
        // Nothing to ask (a floating third-party image with no probe): no running value and no probe.
        assertEquals(null, items.get("svc.grafana").get("running"));
        assertEquals(false, items.get("svc.grafana").get("liveProbe"));
    }

    @Test
    void npmEntriesCarryTheLockedVersionAndFloatingOnesSaySo() {
        Map<String, Map<String, Object>> items = specItemsById(newService(prompt -> Optional.empty()).get());

        assertEquals("latest", items.get("fe.react").get("declared"));
        assertEquals("floating", items.get("fe.react").get("track"));
        assertTrue(items.get("fe.react").get("locked") != null, "the version package-lock.json actually builds is shown next to the floating range");
    }
}
