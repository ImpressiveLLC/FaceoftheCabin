package com.cabin.orchestrator.integrations.cameras;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 2026-08-16: Blink's own clip/motion API is a confirmed dead end for this
 * account -- live diagnostics on the M920q showed AldrichFront and the
 * cabin's own driveway camera identical on every attribute blinkpy exposes
 * (subscription, sync-module arm state, motion_enabled, sync availability),
 * yet only driveway ever produces a clip. Whatever differs lives entirely
 * in Blink's backend, invisible to any client API. The one channel that
 * DOES reliably fire for every real motion event is the phone's own Blink
 * push notification, so this endpoint lets a notification-listener
 * automation on the phone (e.g. Tasker/MacroDroid watching for the Blink
 * app's notification) call straight through to blinkbridge's proven-working
 * manual liveview trigger -- see BlinkLiveviewService.
 *
 * Deliberately NOT under /api/camera/** -- that prefix requires a Google
 * user token (WebConfig), which an unattended phone automation can't
 * produce. Gated instead by a shared-secret header, same model as
 * TechIdController's X-Tech-Id-Api-Key: the caller is an automated agent
 * acting on the account owner's own behalf, not a signed-in human.
 *
 * No separate dedup/replay layer here on purpose -- blinkbridge's own
 * start_liveview() is already idempotent (extends an active session rather
 * than starting a second one), so a double-fired notification is harmless.
 *
 * Reads the body as a raw string and parses it manually instead of using
 * @RequestBody's normal content-negotiated binding -- found live 2026-08-16
 * that MacroDroid's HTTP Request action returned 415 Unsupported Media Type
 * because its own "Content type" body setting was left at "(Not set)",
 * independent of whatever Content-Type header was added manually. A
 * consumer phone-automation app is exactly the kind of caller that can't
 * be trusted to get content negotiation right, so this endpoint doesn't
 * depend on it. `camera` is also accepted as a query param for the same
 * reason -- one less thing that has to be configured correctly on the
 * phone side.
 */
@RestController
@RequestMapping("/api/webhooks/blink-motion")
@CrossOrigin
public class BlinkMotionWebhookController {

    private static final Logger log = LoggerFactory.getLogger(BlinkMotionWebhookController.class);

    @Value("${cabin.blinkMotionWebhook.apiKey:}")
    private String apiKey;

    private final BlinkLiveviewService liveviewService;
    private final ObjectMapper mapper = new ObjectMapper();

    public BlinkMotionWebhookController(BlinkLiveviewService liveviewService) {
        this.liveviewService = liveviewService;
    }

    @PostMapping
    public ResponseEntity<?> trigger(
            @RequestParam(name = "camera", required = false) String cameraQueryParam,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Blink motion webhook is not configured on this instance (cabin.blinkMotionWebhook.apiKey unset)."));
        }
        String presented = request.getHeader("X-Blink-Motion-Api-Key");
        if (presented == null || !apiKey.equals(presented)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Missing or invalid X-Blink-Motion-Api-Key."));
        }
        String cameraName = cameraQueryParam;
        if ((cameraName == null || cameraName.isBlank()) && rawBody != null && !rawBody.isBlank()) {
            try {
                JsonNode node = mapper.readTree(rawBody);
                cameraName = node.path("camera").asText(null);
            } catch (Exception e) {
                return ResponseEntity.badRequest().body(Map.of("error", "Malformed JSON body: " + e.getMessage()));
            }
        }
        if (cameraName == null || cameraName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "camera is required, as a query param or JSON body field."));
        }
        if (!liveviewService.blinkCameraMap().containsKey(cameraName)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown camera: " + cameraName));
        }
        BlinkLiveviewService.Result result = liveviewService.start(cameraName);
        log.info("Blink motion webhook triggered liveview start for {}: ok={}", cameraName, result.ok());
        if (!result.ok()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("ok", false, "camera", cameraName, "error", result.error() != null ? result.error() : "blinkbridge returned an error"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "camera", cameraName));
    }
}
