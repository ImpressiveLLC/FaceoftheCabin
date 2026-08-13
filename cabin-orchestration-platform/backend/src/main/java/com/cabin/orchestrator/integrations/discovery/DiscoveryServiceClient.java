package com.cabin.orchestrator.integrations.discovery;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceDiscoveryResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Talks to the cabin-discovery service (self-discovery/onboarding
 * assistant -- see docs/ontology.yaml's device_discovery_result entity).
 * Never touches the device registry directly: returns a proposal for
 * DeviceDiscoveryController to persist. Applying it to a real device is a
 * separate, explicit, person-approved step (DeviceRegistry.
 * saveConfiguration()/replaceConfiguration()).
 *
 * Falls back to a local-only summary (built from whatever the discovering
 * integration already reported) whenever the service is unreachable or
 * unconfigured (e.g. ANTHROPIC_API_KEY unset on cabin-discovery) -- this
 * degrades the feature, it never blocks the review flow.
 */
@Component
public class DiscoveryServiceClient {

    private static final Logger log = LoggerFactory.getLogger(DiscoveryServiceClient.class);

    @Value("${cabin.discovery.url:http://cabin-discovery:8000}")
    private String discoveryUrl;

    private final RestTemplate rest = new RestTemplate();

    public DeviceDiscoveryResult runDiscovery(String runId, DeviceDescriptor descriptor,
                                               Map<String, Object> discoveryAttributes) {
        Instant requestedAt = Instant.now();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vendor", discoveryAttributes.getOrDefault("vendor", ""));
            body.put("model", discoveryAttributes.getOrDefault("model", ""));
            body.put("description", discoveryAttributes.getOrDefault("description", ""));
            body.put("protocolAdapter", descriptor.protocolAdapter());
            body.put("connectionString", descriptor.connectionString());
            body.put("deviceType", descriptor.type().name());
            body.put("capabilities", descriptor.capabilities().stream().map(Enum::name).sorted().toList());
            body.put("discoveryAttributes", discoveryAttributes);

            DiscoverResponse response = rest.postForObject(discoveryUrl + "/discover", body, DiscoverResponse.class);
            if (response == null || response.matches == null || response.matches.isEmpty()) {
                return localOnlyFallback(runId, descriptor, discoveryAttributes, requestedAt);
            }
            return new DeviceDiscoveryResult(runId, descriptor.deviceId(), requestedAt, null, response.matches);
        } catch (Exception e) {
            log.warn("cabin-discovery unavailable for {}, falling back to local-only: {}",
                descriptor.deviceId(), e.getMessage());
            return localOnlyFallback(runId, descriptor, discoveryAttributes, requestedAt);
        }
    }

    private DeviceDiscoveryResult localOnlyFallback(String runId, DeviceDescriptor descriptor,
                                                      Map<String, Object> discoveryAttributes, Instant requestedAt) {
        String vendor = String.valueOf(discoveryAttributes.getOrDefault("vendor", "")).trim();
        String model = String.valueOf(discoveryAttributes.getOrDefault("model", "")).trim();
        String description = String.valueOf(discoveryAttributes.getOrDefault("description", "")).trim();
        String summary = (vendor.isBlank() && model.isBlank() && description.isBlank())
            ? "The discovery service didn't respond, and this device's discovery metadata doesn't include "
                + "a vendor, model, or description to identify it from."
            : (vendor + " " + model + (description.isBlank() ? "" : " -- " + description)).trim();
        DeviceDiscoveryResult.Match match = new DeviceDiscoveryResult.Match(
            summary, "low", descriptor.name(), descriptor.type(), descriptor.capabilities(),
            new DeviceDiscoveryResult.InstallGuide("linkonly",
                "The discovery service didn't respond, so this reflects only what " + descriptor.protocolAdapter()
                    + " discovery already reported -- nothing external was looked up."),
            List.of());
        return new DeviceDiscoveryResult(runId, descriptor.deviceId(), requestedAt, null, List.of(match));
    }

    /** Minimal shape matching the Python service's /discover response body. */
    private static class DiscoverResponse {
        public List<DeviceDiscoveryResult.Match> matches;
    }
}
