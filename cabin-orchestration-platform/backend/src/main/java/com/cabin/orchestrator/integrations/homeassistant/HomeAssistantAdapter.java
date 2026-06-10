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
 * Adapter for Home Assistant REST API.
 * Covers: locks, Bosch dishwasher, LG washer, Kidde smoke alarm (via HA community integrations),
 * Nest thermostat (via Google SDM in HA), Zigbee/Z-Wave devices bridged through HA.
 *
 * connectionString format: "entity_id" — e.g. "lock.front_door", "sensor.water_pressure"
 */
@Component
public class HomeAssistantAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(HomeAssistantAdapter.class);

    @Value("${cabin.homeassistant.url:http://localhost:8123}")
    private String haUrl;

    @Value("${cabin.homeassistant.token:}")
    private String haToken;

    private final RestTemplate rest = new RestTemplate();

    @Override
    public String adapterType() {
        return "ha_rest";
    }

    @Override
    public Optional<DeviceStatus> fetchState(DeviceDescriptor descriptor) {
        if (haToken.isBlank()) {
            log.warn("HA token not configured — skipping {}", descriptor.deviceId());
            return Optional.empty();
        }
        try {
            HttpHeaders headers = bearerHeaders();
            ResponseEntity<Map> response = rest.exchange(
                haUrl + "/api/states/" + descriptor.connectionString(),
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> body = response.getBody();
                String state = String.valueOf(body.get("state"));
                Map<?, ?> attrs = (Map<?, ?>) body.getOrDefault("attributes", Map.of());
                Map<String, Object> attributes = new LinkedHashMap<>();
                attrs.forEach((k, v) -> attributes.put(String.valueOf(k), v));
                return Optional.of(new DeviceStatus(
                    descriptor.deviceId(), descriptor.type(), descriptor.name(),
                    mapHaState(state), Instant.now(), attributes));
            }
        } catch (Exception e) {
            log.warn("HA fetch failed for {}: {}", descriptor.deviceId(), e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public boolean sendCommand(DeviceDescriptor descriptor, String command, Object payload) {
        // Map command to HA service call
        // e.g. command="lock" → POST /api/services/lock/lock {entity_id: ...}
        String[] parts = command.split("\\.");   // "lock.lock" or "climate.set_temperature"
        if (parts.length < 2) return false;
        try {
            HttpHeaders headers = bearerHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("entity_id", descriptor.connectionString());
            if (payload != null) body.put("data", payload);
            rest.exchange(haUrl + "/api/services/" + parts[0] + "/" + parts[1],
                HttpMethod.POST, new HttpEntity<>(body, headers), Void.class);
            return true;
        } catch (Exception e) {
            log.error("HA command failed for {}: {}", descriptor.deviceId(), e.getMessage());
            return false;
        }
    }

    private HttpHeaders bearerHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(haToken);
        return h;
    }

    private String mapHaState(String haState) {
        return switch (haState.toLowerCase()) {
            case "on", "locked", "home", "heating", "cooling", "idle" -> "ONLINE";
            case "off", "unlocked" -> "ONLINE";  // device online, state is "off"
            case "unavailable", "unknown" -> "UNKNOWN";
            case "alarm_triggered" -> "ALARM";
            default -> "ONLINE";
        };
    }
}
