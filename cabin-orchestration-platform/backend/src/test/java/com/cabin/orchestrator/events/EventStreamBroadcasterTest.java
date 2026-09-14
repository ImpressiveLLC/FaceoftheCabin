package com.cabin.orchestrator.events;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D20: EventStreamBroadcaster has no servlet container in this plain-JUnit
 * test (matching SignalQualityControllerTest's own convention -- no
 * MockMvc/@SpringBootTest for a class this small). Two real limits found
 * writing this, not assumed: (1) SseEmitter.send() throws without a real
 * HttpServletResponse, since Spring only wires that up during genuine async
 * dispatch; (2) emitter.complete()/.completeWithError() called from plain
 * application code (no servlet container) do NOT fire the onCompletion/
 * onError callbacks this class relies on for cleanup -- ResponseBodyEmitter
 * only triggers those once Spring's own request-handling machinery
 * (a Handler, set via initialize(), never called outside a real request)
 * signals back that the underlying request actually finished. Both are
 * genuinely live-only behaviors, not something a stubbed-out test can prove
 * without reimplementing that machinery -- verified against a real running
 * instance instead (see PR description / MAINTENANCE.md). What IS testable
 * in isolation, and tested here: subscriber bookkeeping.
 */
class EventStreamBroadcasterTest {

    private CabinEvent sampleEvent() {
        return new CabinEvent("evt-1", "z2m-temp_kitchen", "TELEMETRY", "INFO",
            Instant.now(), Map.of("temperature", 68));
    }

    @Test
    void subscribeIncrementsSubscriberCount() {
        EventStreamBroadcaster broadcaster = new EventStreamBroadcaster();
        assertEquals(0, broadcaster.subscriberCount());

        broadcaster.subscribe();
        broadcaster.subscribe();

        assertEquals(2, broadcaster.subscriberCount());
    }

    @Test
    void broadcastWithNoSubscribersIsANoOp() {
        EventStreamBroadcaster broadcaster = new EventStreamBroadcaster();

        assertDoesNotThrow(() -> broadcaster.broadcast(sampleEvent()));
    }

}
