package com.cabin.orchestrator.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * D20 (Cabin Platform Decisions artifact): the Live MQTT tile used to
 * connect a raw browser WebSocket straight to mosquitto -- an
 * unauthenticated, unscoped MQTT pub/sub channel (device control, not
 * just telemetry). This replaces that with a real, read-only, backend-
 * mediated relay: every already-persisted CabinEvent gets pushed here
 * (see EventConsumer's pollLoop, right after eventService.save()) and
 * fanned out to whichever browsers are currently subscribed via
 * GET /api/events/live. No inbound channel exists at all -- SSE is
 * one-directional by construction, so there is no publish-capability
 * risk even in principle, unlike a raw broker connection.
 *
 * Gated exactly like the bare GET /api/events collection it mirrors
 * (same /api/events/** prefix in WebConfig/GoogleAuthInterceptor) --
 * this deliberately does not invent a separate, narrower gate.
 */
@Component
public class EventStreamBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventStreamBroadcaster.class);

    // No fixed timeout -- a browser tab can stay open indefinitely; cleanup
    // happens via the emitter's own onCompletion/onTimeout/onError callbacks
    // below, not a server-side deadline.
    private static final long NO_TIMEOUT = 0L;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(NO_TIMEOUT);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    /** Called once per already-persisted CabinEvent -- see EventConsumer.pollLoop(). */
    public void broadcast(CabinEvent event) {
        if (emitters.isEmpty()) return;
        String json;
        try {
            json = mapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("Failed to serialize event {} for live stream: {}", event.eventId(), e.getMessage());
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("cabin-event").data(json));
            } catch (IOException | IllegalStateException e) {
                // Expected whenever a browser tab just closed/navigated away --
                // not a real error, matches CameraMediaController's own
                // "client disconnect is routine" precedent for its live stream.
                emitters.remove(emitter);
            }
        }
    }

    /** Exposed for tests and /api/system/health-style introspection, not otherwise consumed today. */
    public int subscriberCount() {
        return emitters.size();
    }
}
