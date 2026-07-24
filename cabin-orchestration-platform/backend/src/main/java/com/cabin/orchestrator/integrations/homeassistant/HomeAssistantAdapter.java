package com.cabin.orchestrator.integrations.homeassistant;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;

/**
 * Adapter for Home Assistant REST API — location-aware.
 *
 * Routes to cabin-hub or home-hub HA instance based on DeviceDescriptor.location().
 * Covers all ha_rest devices at both locations:
 *   cabin — locks, Kidde smoke, thermostat, Bosch dishwasher, LG washer
 *   home  — Kwikset 916 Zigbee locks, meross MTS300M Matter thermostat, Kidde P4010ACSCO-WF,
 *            Emporia Vue Gen 3, LG ThinQ washer/dryer, Bosch 500 dishwasher, Daikin Aurora HVAC
 *
 * connectionString format: HA entity_id — e.g. "lock.home_front_door"
 */
@Component
public class HomeAssistantAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantAdapter.class);

    // Cabin HA instance (default: runs on cabin-hub via Tailscale or localhost in dev)
    @Value("${cabin.homeassistant.url:http://localhost:8123}")
    private String cabinHaUrl;

    @Value("${cabin.homeassistant.token:}")
    private String cabinHaToken;

    // Home HA instance (runs on home-hub via Tailscale)
    @Value("${cabin.locations.home.homeassistant.url:http://home-hub:8123}")
    private String homeHaUrl;

    @Value("${cabin.locations.home.homeassistant.token:}")
    private String homeHaToken;

    private final RestTemplate rest = new RestTemplate();

    @Override
    public String adapterType() {
        return "ha_rest";
    }

    @Override
    public Optional<DeviceStatus> fetchState(DeviceDescriptor descriptor) {
        String token = tokenFor(descriptor);
        if (token.isBlank()) {
            log.warn("HA token not configured for location '{}' — skipping {}",
                descriptor.location(), descriptor.deviceId());
            return Optional.empty();
        }
        try {
            HttpHeaders headers = bearerHeaders(token);
            ResponseEntity<Map> response = rest.exchange(
                urlFor(descriptor) + "/api/states/" + descriptor.connectionString(),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> body = response.getBody();
                String state = String.valueOf(body.get("state"));
                Object attrsObj = body.getOrDefault("attributes", Map.of());
                Map<?, ?> attrs = attrsObj instanceof Map<?, ?> m ? m : Map.of();
                Map<String, Object> attributes = new LinkedHashMap<>();
                attrs.forEach((k, v) -> attributes.put(String.valueOf(k), v));
                return Optional.of(new DeviceStatus(
                    descriptor.deviceId(), descriptor.type(), descriptor.name(),
                    mapHaState(state), Instant.now(), attributes, descriptor.location()));
            }
        } catch (Exception e) {
            log.warn("HA fetch failed for {}: {}", descriptor.deviceId(), e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public boolean sendCommand(DeviceDescriptor descriptor, String command, Object payload) {
        String[] parts = command.split("\\.");   // "lock.lock" or "climate.set_temperature"
        if (parts.length < 2) return false;
        String token = tokenFor(descriptor);
        if (token.isBlank()) {
            log.warn("HA token not configured for location '{}' — cannot send command to {}",
                descriptor.location(), descriptor.deviceId());
            return false;
        }
        try {
            HttpHeaders headers = bearerHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("entity_id", descriptor.connectionString());
            if (payload != null) body.put("data", payload);
            rest.exchange(urlFor(descriptor) + "/api/services/" + parts[0] + "/" + parts[1],
                HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
            return true;
        } catch (Exception e) {
            log.error("HA command failed for {}: {}", descriptor.deviceId(), e.getMessage());
            return false;
        }
    }

    private String urlFor(DeviceDescriptor d) {
        return "home".equals(d.location()) ? homeHaUrl : cabinHaUrl;
    }

    private String tokenFor(DeviceDescriptor d) {
        return "home".equals(d.location()) ? homeHaToken : cabinHaToken;
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private String mapHaState(String haState) {
        return switch (haState.toLowerCase()) {
            case "on", "locked", "home", "heating", "cooling", "idle" -> "ONLINE";
            case "off", "unlocked" -> "ONLINE";
            case "unavailable", "unknown" -> "UNKNOWN";
            case "alarm_triggered" -> "ALARM";
            default -> "ONLINE";
        };
    }
}
