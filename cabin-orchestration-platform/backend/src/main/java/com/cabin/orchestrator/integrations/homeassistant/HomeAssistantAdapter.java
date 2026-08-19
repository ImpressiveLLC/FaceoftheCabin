package com.cabin.orchestrator.integrations.homeassistant;

import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    // Found 2026-08-12 (user report: Kidde CO alarm and other HA-only
    // devices never appearing in the UI, despite PR #1 explicitly adding
    // Kidde support): these @Value property paths never matched what's
    // actually deployed. docker-compose.m920q.yml sets HA_URL/HA_TOKEN
    // (cabin) and HOME_HA_URL/HOME_HA_TOKEN (home) -- both directly, not
    // nested under a "cabin.*" prefix. The old paths here
    // (cabin.homeassistant.token / cabin.locations.home.homeassistant.token)
    // resolved to Spring env vars CABIN_HOMEASSISTANT_TOKEN / CABIN_
    // LOCATIONS_HOME_HOMEASSISTANT_TOKEN, which were never set anywhere,
    // so cabinHaToken/homeHaToken were always empty strings. discover()'s
    // blank-token check (below) returns List.of() silently -- no
    // exception, no log line, unlike fetchState()'s equivalent check a
    // few lines down which does log a warning -- so this was invisible in
    // normal operation: the scheduled discovery task ran every 60s,
    // forever, and never found anything, with nothing in the logs to
    // suggest why. HomeAssistantDiscoveryService/HomeAssistantAdapter had
    // never actually worked on this deployment, for either location,
    // since the day they were written.
    @Value("${HA_URL:http://localhost:8123}")
    private String cabinHaUrl;

    @Value("${HA_TOKEN:}")
    private String cabinHaToken;

    @Value("${HOME_HA_URL:http://home-hub:8123}")
    private String homeHaUrl;

    @Value("${HOME_HA_TOKEN:}")
    private String homeHaToken;

    private final RestTemplate rest = new RestTemplate();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    public record DiscoveredEntity(String entityId, String state, Map<String, Object> attributes) {}

    @Override
    public String adapterType() {
        return "ha_rest";
    }

    /** Fetch the HA state catalog used by candidate and presence discovery. */
    public List<DiscoveredEntity> discover(String location) {
        String token = "home".equals(location) ? homeHaToken : cabinHaToken;
        String url = "home".equals(location) ? homeHaUrl : cabinHaUrl;
        // Found 2026-08-12: this silently returning empty with no log line
        // is exactly how a real property-binding bug (see the token fields'
        // own comment) went undetected -- the scheduled discovery task ran
        // every 60s forever and found nothing, with nothing anywhere to
        // suggest why. Log once per call now so a misconfigured/missing
        // token is visible instead of indistinguishable from "HA legitimately
        // has nothing new to discover."
        if (token.isBlank()) {
            // WARN, not debug: a missing token is a persistent configuration
            // problem, not a transient blip (that's the catch block below) --
            // it deserves to be visible at default log levels.
            log.warn("HA discovery skipped for '{}' — no token configured", location);
            return List.of();
        }
        try {
            ResponseEntity<List> response = rest.exchange(url + "/api/states", HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), List.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("HA discovery for '{}' returned {}", location, response.getStatusCode());
                return List.of();
            }
            List<DiscoveredEntity> found = new ArrayList<>();
            for (Object raw : response.getBody()) {
                if (!(raw instanceof Map<?, ?> row)) continue;
                String entityId = String.valueOf(row.get("entity_id"));
                String state = String.valueOf(row.get("state"));
                Map<String, Object> attrs = new LinkedHashMap<>();
                Object attrRaw = row.get("attributes");
                if (attrRaw instanceof Map<?, ?> map) map.forEach((k, v) -> attrs.put(String.valueOf(k), v));
                found.add(new DiscoveredEntity(entityId, state, attrs));
            }
            return found;
        } catch (Exception e) {
            log.debug("HA discovery unavailable for {}: {}", location, e.getMessage());
            return List.of();
        }
    }

    public String normalizedState(String state) { return mapHaState(state); }

    /**
     * Maps every currently-known entity_id to its HA device registry id, in
     * one call. /api/states (used by discover() above) has no device_id of
     * its own -- HA's device grouping (which entities belong to the same
     * physical thing, e.g. the Liebherr fridge's 9 separate number/select/
     * sensor/switch entities) only exists via the template engine's builtin
     * device_id() function or the WebSocket API; this uses /api/template
     * (plain REST, same auth as everything else here) since it's the one
     * that doesn't require a second client/protocol just for this.
     *
     * 2026-08-18: added for HomeAssistantDiscoveryService's composite-device
     * grouping (see its own comment) after live investigation confirmed the
     * Liebherr fridge and Kidde each surface as several unrelated
     * candidates today with no shared identity. Not yet verified against a
     * live response -- HA_TOKEN/HOME_HA_TOKEN are both currently blank in
     * the M920q's deployed .env (a separate, blocking infra gap, see
     * docs/DEFINITION_OF_DONE.md), so discover() itself has been returning
     * List.of() the same way this will. device_id() is a long-standing,
     * documented HA template builtin, not something version-fragile, but
     * this still needs a real run against a working token to confirm the
     * exact response shape once that's restored. Same fail-safe shape as
     * discover(): any problem (blank token, unreachable HA, unexpected
     * response, template error) returns an empty map rather than throwing,
     * so a caller can always treat "not grouped" as the safe default.
     */
    public Map<String, String> deviceIdsByEntity(String location) {
        String token = "home".equals(location) ? homeHaToken : cabinHaToken;
        String url = "home".equals(location) ? homeHaUrl : cabinHaUrl;
        if (token.isBlank()) {
            return Map.of();
        }
        try {
            HttpHeaders headers = bearerHeaders(token);
            headers.setContentType(MediaType.APPLICATION_JSON);
            // Jinja2, HA's own template language: builds {entity_id: device_id}
            // for every entity that actually belongs to a device (device_id()
            // returns None -- rendered "null" -- for helpers/templates that
            // don't, which the JSON parse below simply drops).
            String template = "{% set ns = namespace(result={}) %}"
                + "{% for s in states %}"
                + "{% set ns.result = dict(ns.result, **{s.entity_id: device_id(s.entity_id)}) %}"
                + "{% endfor %}"
                + "{{ ns.result | tojson }}";
            Map<String, Object> body = Map.of("template", template);
            ResponseEntity<String> response = rest.exchange(url + "/api/template", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("HA device_id template for '{}' returned {}", location, response.getStatusCode());
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            JsonNode root = jsonMapper.readTree(response.getBody());
            root.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    result.put(entry.getKey(), entry.getValue().asText());
                }
            });
            return result;
        } catch (Exception e) {
            log.debug("HA device_id lookup unavailable for {}: {}", location, e.getMessage());
            return Map.of();
        }
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
                Object attrsObj = body.get("attributes");
                Map<?, ?> attrs = (attrsObj instanceof Map) ? (Map<?, ?>) attrsObj : Collections.emptyMap();
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
