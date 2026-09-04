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
        assertTrue(rendered.contains("No refrigeration appliance is currently paired"),
            "must not fabricate a Liebherr or other fridge that was never actually confirmed installed");
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
}
