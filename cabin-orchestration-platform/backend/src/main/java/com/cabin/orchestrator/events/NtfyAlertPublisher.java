package com.cabin.orchestrator.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Pushes a phone notification via ntfy.sh for CRITICAL-severity CabinEvents
 * (water leak / smoke / alarm) — same delivery mechanism as the existing
 * Node-RED overnight-camera-alert flow (docs/ontology.yaml's
 * cabin_camera_overnight_alert), reused here instead of standing up a
 * second notification path. WARN-severity events (door open, low battery,
 * tamper) are deliberately not pushed — that would page for every open door.
 *
 * An event can ask for a push regardless of severity with payload notify=true
 * (W-11's 80% storage warning is WARN and should still reach a phone). It may
 * also supply its own ntfy title and body in payload title/message; without
 * them the push reads "deviceId: eventType" as before. Non-CRITICAL pushes go
 * out at ntfy priority "high", CRITICAL at "urgent".
 *
 * No-op when cabin.alerts.ntfyTopic is unset, matching this codebase's
 * existing pattern for optional integrations (see CameraMediaController's
 * blinkBridgeUrl / techid.apiKey).
 */
@Component
public class NtfyAlertPublisher {

    private static final Logger log = LoggerFactory.getLogger(NtfyAlertPublisher.class);

    private final String ntfyTopic;
    private final String ntfyBaseUrl;

    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    public NtfyAlertPublisher(
            @Value("${cabin.alerts.ntfyTopic:}") String ntfyTopic,
            // Overridable so tests can point this at a local server instead
            // of ntfy.sh — not something an operator needs to change.
            @Value("${cabin.alerts.ntfyBaseUrl:https://ntfy.sh}") String ntfyBaseUrl) {
        this.ntfyTopic = ntfyTopic;
        this.ntfyBaseUrl = ntfyBaseUrl;
    }

    public void publishIfCritical(CabinEvent event) {
        boolean critical = "CRITICAL".equals(event.severity());
        boolean asked = event.payload() != null && Boolean.TRUE.equals(event.payload().get("notify"));
        if (!critical && !asked) return;
        if (ntfyTopic == null || ntfyTopic.isBlank()) {
            log.debug("CRITICAL event {} not pushed — no ntfy topic configured", event.eventId());
            return;
        }
        try {
            String message = payloadText(event, "message", event.sourceDeviceId() + ": " + event.eventType());
            String title = payloadText(event, "title", "Cabin Alert");
            HttpRequest req = HttpRequest.newBuilder(URI.create(ntfyBaseUrl + "/" + ntfyTopic))
                .timeout(Duration.ofSeconds(10))
                .header("Title", headerSafe(title))
                .header("Priority", critical ? "urgent" : "high")
                .POST(HttpRequest.BodyPublishers.ofString(message, StandardCharsets.UTF_8))
                .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding())
                .exceptionally(ex -> {
                    log.warn("ntfy push failed for event {}: {}", event.eventId(), ex.getMessage());
                    return null;
                });
        } catch (Exception e) {
            log.warn("Failed to build ntfy push for event {}: {}", event.eventId(), e.getMessage());
        }
    }

    private static String payloadText(CabinEvent event, String key, String fallback) {
        Object v = event.payload() == null ? null : event.payload().get(key);
        return v instanceof String s && !s.isBlank() ? s : fallback;
    }

    /** HTTP header values cannot carry control characters or non-ASCII; keep the title plain. */
    private static String headerSafe(String text) {
        return text.replaceAll("[^\\x20-\\x7E]", " ").trim();
    }
}
