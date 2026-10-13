package com.cabin.orchestrator.api;

import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * W-34: GET /api/presence now says how old the signal is. The existing fields
 * (profile, label, options, autoDerived, signals) must be untouched: cabin-ui's
 * presence picker reads them and changing their shape would be a regression.
 */
class PresenceControllerTest {

    private PresenceSignalRegistry registry;
    private PresenceController controller;

    @BeforeEach
    void setUp() {
        registry = new PresenceSignalRegistry();
        PresenceService service = new PresenceService(mock(JdbcTemplate.class), registry);
        controller = new PresenceController(service, registry);
    }

    private static long ageOf(Map<String, Object> body) {
        return (Long) body.get("signalAgeSeconds");
    }

    @Test
    void withNoHeartbeatTheFreshnessFieldsAreInactive() {
        registry.record("cabin", "nate", false);

        Map<String, Object> body = controller.get();

        assertNull(body.get("lastSeen"));
        assertNull(body.get("signalAgeSeconds"));
        assertEquals(false, body.get("stale"), "no heartbeat means unknown age, never 'stale'");
        assertEquals(6.0, body.get("staleAfterHours"));
    }

    @Test
    void aRecentHeartbeatReportsItsAgeAndIsNotStale() {
        registry.recordSeen("nate", Instant.now().minusSeconds(3600));

        Map<String, Object> body = controller.get();

        assertEquals(false, body.get("stale"));
        assertTrue(Math.abs(ageOf(body) - 3600) < 30, "age ~ 1 h, was " + ageOf(body));
        assertNotNull(body.get("lastSeen"));
        assertDoesNotThrow(() -> Instant.parse((String) body.get("lastSeen")), "lastSeen must be an ISO instant the UI can parse");
    }

    @Test
    void aHeartbeatOlderThanTheLimitIsStale() {
        registry.recordSeen("nate", Instant.now().minusSeconds(7 * 3600));

        Map<String, Object> body = controller.get();

        assertEquals(true, body.get("stale"));
    }

    @Test
    void theLimitComesFromConfiguration() {
        ReflectionTestUtils.setField(controller, "staleAfterHours", 12.0);
        registry.recordSeen("nate", Instant.now().minusSeconds(7 * 3600));

        Map<String, Object> body = controller.get();

        assertEquals(false, body.get("stale"));
        assertEquals(12.0, body.get("staleAfterHours"));
    }

    @Test
    void theNewestHeartbeatAcrossPeopleDecidesStaleness() {
        registry.recordSeen("emma", Instant.now().minusSeconds(30 * 3600));
        registry.recordSeen("nate", Instant.now().minusSeconds(600));

        assertEquals(false, controller.get().get("stale"),
            "someone reporting recently means the picture is current");
    }

    @Test
    void theExistingResponseShapeIsUntouched() {
        registry.record("cabin", "nate", true);
        registry.recordSeen("nate", Instant.now());

        Map<String, Object> body = controller.get();

        for (String key : new String[] {"profile", "label", "options", "autoDerived", "signals"}) {
            assertTrue(body.containsKey(key), "cabin-ui's presence picker still reads '" + key + "'");
        }
        assertEquals(1, ((java.util.List<?>) body.get("signals")).size(),
            "the heartbeat is not a presence signal and must not appear in signals[]");
    }
}
