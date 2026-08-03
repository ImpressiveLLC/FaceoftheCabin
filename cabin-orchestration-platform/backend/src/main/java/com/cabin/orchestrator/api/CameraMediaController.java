package com.cabin.orchestrator.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Proxies camera media (snapshot, clip, live stream) from Frigate through
 * cabin-backend so cabin-ui's authenticated Camera Events panel can show
 * real video/images without exposing Frigate's own admin API/UI publicly
 * (Frigate itself stays Tailscale-only, same reasoning as Node-RED/HA —
 * see ROADMAP.md's Phase 6). Gated by GoogleAuthInterceptor via
 * WebConfig's /api/camera/** pattern — this is meaningfully more
 * sensitive than the metadata-only public activity widget (Phase 4).
 *
 * Frigate's real endpoint shapes confirmed by reading its installed
 * source directly (frigate/api/media.py) on the M920q, not assumed from
 * generic docs:
 *   GET /api/events/{event_id}/snapshot.jpg
 *   GET /api/events/{event_id}/clip.mp4
 *   GET /api/{camera_name}                   (MJPEG live stream)
 * {event_id} here is Frigate's own event id (the "frigateEventId" field
 * MqttBridgeService now captures from the MQTT events payload), not
 * cabin-backend's own CabinEvent.eventId.
 */
@RestController
@RequestMapping("/api/camera")
@CrossOrigin
public class CameraMediaController {

    private static final Logger log = LoggerFactory.getLogger(CameraMediaController.class);

    @Value("${cabin.devices.cameras.frigateUrl:http://frigate:5000}")
    private String frigateUrl;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    @GetMapping(value = "/events/{frigateEventId}/snapshot")
    public ResponseEntity<byte[]> snapshot(@PathVariable String frigateEventId) {
        return proxyBytes("/api/events/" + frigateEventId + "/snapshot.jpg", MediaType.IMAGE_JPEG);
    }

    @GetMapping(value = "/events/{frigateEventId}/clip")
    public ResponseEntity<byte[]> clip(@PathVariable String frigateEventId) {
        return proxyBytes("/api/events/" + frigateEventId + "/clip.mp4", MediaType.valueOf("video/mp4"));
    }

    /** MJPEG live stream, proxied continuously — not buffered like snapshot/clip above. */
    @GetMapping(value = "/{cameraName}/live")
    public ResponseEntity<StreamingResponseBody> live(@PathVariable String cameraName) {
        String url = frigateUrl + "/api/" + cameraName;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build();

        StreamingResponseBody body = (OutputStream out) -> {
            try {
                HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() != 200) {
                    log.warn("Frigate live stream for {} returned {}", cameraName, resp.statusCode());
                    return;
                }
                try (InputStream in = resp.body()) {
                    in.transferTo(out);
                }
            } catch (IOException | InterruptedException e) {
                // Expected whenever the client just closes the tab/panel —
                // not a real error, don't log at warn for the common case.
                log.debug("Live stream for {} ended: {}", cameraName, e.getMessage());
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.valueOf("multipart/x-mixed-replace; boundary=frame"))
            .body(body);
    }

    private ResponseEntity<byte[]> proxyBytes(String path, MediaType contentType) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(frigateUrl + path))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 404) {
                return ResponseEntity.notFound().build();
            }
            if (resp.statusCode() != 200) {
                log.warn("Frigate returned {} for {}", resp.statusCode(), path);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
            }
            return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=86400")
                .body(resp.body());
        } catch (IOException | InterruptedException e) {
            log.warn("Failed to proxy {} from Frigate: {}", path, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
