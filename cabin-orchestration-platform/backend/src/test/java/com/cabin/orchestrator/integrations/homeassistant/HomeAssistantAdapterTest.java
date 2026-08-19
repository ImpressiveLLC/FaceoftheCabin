package com.cabin.orchestrator.integrations.homeassistant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Found 2026-08-12 (user report: Kidde CO alarm, the Liebherr fridge, and
 * every other Home Assistant-only device never appearing in the UI,
 * despite PR #1 explicitly adding Kidde support): this class had zero
 * test coverage, which is exactly how its real bug survived undetected --
 * its four @Value property paths (cabin.homeassistant.token,
 * cabin.locations.home.homeassistant.token, etc.) never matched what
 * docker-compose.m920q.yml actually sets (HA_TOKEN, HA_URL, HOME_HA_TOKEN,
 * HOME_HA_URL, unnested). The tokens silently resolved to empty strings,
 * and discover()'s blank-token check returns List.of() with no
 * exception and no log line -- so the scheduled discovery task ran every
 * 60s, forever, and found nothing, with nothing anywhere suggesting why.
 * See HomeAssistantAdapter's own field comments for the full incident.
 */
class HomeAssistantAdapterTest {

    /**
     * Reads each field's @Value annotation directly via reflection and
     * asserts it references the exact env var docker-compose.m920q.yml
     * sets -- this is the actual regression guard for the bug: it would
     * have failed on the pre-fix property paths, and fails again if
     * someone reintroduces a nested "cabin.*" nesting that doesn't match
     * what's actually deployed.
     */
    @Test
    void valueAnnotationsMatchTheEnvVarsDockerComposeActuallySets() throws NoSuchFieldException {
        assertValueExpression("cabinHaUrl", "${HA_URL:http://localhost:8123}");
        assertValueExpression("cabinHaToken", "${HA_TOKEN:}");
        assertValueExpression("homeHaUrl", "${HOME_HA_URL:http://home-hub:8123}");
        assertValueExpression("homeHaToken", "${HOME_HA_TOKEN:}");
    }

    private void assertValueExpression(String fieldName, String expected) throws NoSuchFieldException {
        Field field = HomeAssistantAdapter.class.getDeclaredField(fieldName);
        Value value = field.getAnnotation(Value.class);
        assertNotNull(value, fieldName + " must be @Value-annotated");
        assertEquals(expected, value.value(),
            fieldName + "'s property path must match the real docker-compose.m920q.yml env var name");
    }

    @Test
    void discoverReturnsEmptyRatherThanThrowingWhenTokenIsBlank() {
        HomeAssistantAdapter adapter = new HomeAssistantAdapter();
        ReflectionTestUtils.setField(adapter, "cabinHaToken", "");
        ReflectionTestUtils.setField(adapter, "homeHaToken", "");

        assertEquals(List.of(), adapter.discover("cabin"));
        assertEquals(List.of(), adapter.discover("home"));
    }

    /**
     * 2026-08-18: deviceIdsByEntity (composite-device grouping, see
     * HomeAssistantDiscoveryService's own comment) must degrade exactly
     * like discover() does -- confirmed live on the M920q the same
     * session this was added that HA_TOKEN/HOME_HA_TOKEN are currently
     * blank in production, so this fail-safe path is not hypothetical.
     */
    @Test
    void deviceIdsByEntityReturnsEmptyMapRatherThanThrowingWhenTokenIsBlank() {
        HomeAssistantAdapter adapter = new HomeAssistantAdapter();
        ReflectionTestUtils.setField(adapter, "cabinHaToken", "");
        ReflectionTestUtils.setField(adapter, "homeHaToken", "");

        assertEquals(java.util.Map.of(), adapter.deviceIdsByEntity("cabin"));
        assertEquals(java.util.Map.of(), adapter.deviceIdsByEntity("home"));
    }

    @Test
    void adapterTypeIsHaRest() {
        assertEquals("ha_rest", new HomeAssistantAdapter().adapterType());
    }
}
