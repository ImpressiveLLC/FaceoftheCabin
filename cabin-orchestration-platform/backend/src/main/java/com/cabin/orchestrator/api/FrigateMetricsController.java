package com.cabin.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Added 2026-08-08, replacing the Grafana iframe embed after three
 * separate fix attempts (URL/subpath, SameSite cookie, a disproven
 * theory chase) all failed against what turned out to be either
 * browser-level third-party cookie blocking or an iframe request that
 * never reaches the server at all -- see docs/ontology.yaml's
 * cabin_grafana_public_access notes for the full history. User's own
 * call: stop fighting the iframe, query Prometheus directly for the
 * one metric that actually matters (camera health) and link out to the
 * full Grafana dashboard for anyone who wants more.
 *
 * Prometheus itself stays Tailscale/internal-only (never exposed
 * publicly, unlike Grafana's own ill-fated public-embedding attempt) --
 * confirmed live: cabin-backend already reaches it over the shared
 * Docker network (cabin_prometheus:9090, no port published to the
 * host), so this needed zero new network exposure at all.
 */
@RestController
@RequestMapping("/api/frigate-metrics")
@CrossOrigin
public class FrigateMetricsController {

    private static final Logger log = LoggerFactory.getLogger(FrigateMetricsController.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${cabin.prometheus.url:http://prometheus:9090}")
    private String prometheusUrl;

    @GetMapping
    public Map<String, Object> get() {
        try {
            String query = URLEncoder.encode("frigate_camera_fps", StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(prometheusUrl + "/api/v1/query?query=" + query))
                .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            return parseCameraFps(resp.body());
        } catch (Exception e) {
            log.warn("Prometheus query failed: {}", e.getMessage());
            // Honest empty map, matching SecurityController/SignalQualityController's
            // "absent means unknown" pattern -- never a fabricated zero that would
            // read as "camera confirmed down" when it's actually "couldn't ask".
            return Map.of();
        }
    }

    /**
     * Pure parsing, no HTTP -- exported shape so this is directly unit
     * testable against a canned Prometheus response instead of needing
     * to mock HttpClient. Keyed by camera_name (whatever Prometheus
     * actually knows about, not a hardcoded list) -> { cameraFps }.
     */
    Map<String, Object> parseCameraFps(String prometheusJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            JsonNode root = mapper.readTree(prometheusJson);
            for (JsonNode result : root.path("data").path("result")) {
                String camera = result.path("metric").path("camera_name").asText(null);
                if (camera == null) continue;
                double fps = Double.parseDouble(result.path("value").get(1).asText("0"));
                out.put(camera, Map.of("cameraFps", fps));
            }
        } catch (Exception e) {
            log.warn("Failed to parse Prometheus response: {}", e.getMessage());
        }
        return out;
    }
}
