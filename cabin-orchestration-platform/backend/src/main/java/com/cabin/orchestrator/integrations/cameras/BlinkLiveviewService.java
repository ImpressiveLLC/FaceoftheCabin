package com.cabin.orchestrator.integrations.cameras;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Triggers blinkbridge's on-demand Blink liveview session for a given cabin
 * camera name. Extracted from CameraMediaController (2026-08-16) so the
 * same start/stop logic can be called both from the authenticated manual
 * "Watch Live" REST endpoint and from BlinkMotionWebhookController's
 * shared-secret-gated automatic trigger -- see that class's javadoc for why
 * the automatic path exists (Blink's own motion-detected/clip API is a dead
 * end for accounts like this one; the phone's own push notification is the
 * only reliable signal).
 */
@Service
public class BlinkLiveviewService {

    private static final Logger log = LoggerFactory.getLogger(BlinkLiveviewService.class);

    @Value("${cabin.devices.cameras.blinkBridgeUrl:http://blinkbridge:8811}")
    private String blinkBridgeUrl;

    // "cabinCameraName:blinkDeviceName" pairs, comma-separated -- see
    // CameraMediaController's javadoc for the full naming-layer rationale.
    @Value("${cabin.devices.cameras.blinkCameraMap:}")
    private String blinkCameraMapRaw;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public record Result(boolean ok, boolean skipped, int statusCode, String body, String error) {}

    public Map<String, String> blinkCameraMap() {
        Map<String, String> map = new HashMap<>();
        if (blinkCameraMapRaw == null || blinkCameraMapRaw.isBlank()) return map;
        for (String pair : blinkCameraMapRaw.split(",")) {
            int idx = pair.indexOf(':');
            if (idx <= 0) continue;
            map.put(pair.substring(0, idx).trim(), pair.substring(idx + 1).trim());
        }
        return map;
    }

    public Result start(String cameraName) {
        return control(cameraName, "start");
    }

    public Result stop(String cameraName) {
        return control(cameraName, "stop");
    }

    private Result control(String cameraName, String action) {
        String blinkName = blinkCameraMap().get(cameraName);
        if (blinkName == null) {
            return new Result(true, true, 0, null, null);
        }
        try {
            String encoded = java.net.URLEncoder.encode(blinkName, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            HttpRequest req = HttpRequest.newBuilder(URI.create(blinkBridgeUrl + "/liveview/" + encoded + "/" + action))
                .timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("blinkbridge liveview {} for {} returned {}", action, cameraName, resp.statusCode());
                return new Result(false, false, resp.statusCode(), resp.body(), null);
            }
            return new Result(true, false, 200, resp.body(), null);
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to reach blinkbridge for liveview {} on {}: {}", action, cameraName, e.getMessage());
            return new Result(false, false, 0, null, "blinkbridge unreachable");
        }
    }
}
