package com.cabin.orchestrator.api;

import com.cabin.orchestrator.security.SecurityStateRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityControllerTest {

    @SuppressWarnings("unchecked")
    @Test
    void returnsArmedStateKeyedByLocation() {
        SecurityStateRegistry registry = new SecurityStateRegistry();
        registry.record("cabin", true);
        SecurityController controller = new SecurityController(registry);

        Map<String, Object> body = controller.get();

        assertTrue(body.containsKey("cabin"));
        Map<String, Object> cabin = (Map<String, Object>) body.get("cabin");
        assertEquals(true, cabin.get("armed"));
        assertNotNull(cabin.get("lastUpdated"));
    }

    @Test
    void omitsALocationThatHasNeverPublishedASignal() {
        SecurityStateRegistry registry = new SecurityStateRegistry();
        SecurityController controller = new SecurityController(registry);

        Map<String, Object> body = controller.get();

        assertFalse(body.containsKey("home"),
            "a location with no armed_away signal must be absent, not defaulted to false/disarmed");
    }
}
