package com.cabin.orchestrator.api;

import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** W-27/W-28 (2026-10-02): the allowlist is the security boundary -- see HaServiceController's doc. */
class HaServiceControllerTest {

    private final HomeAssistantAdapter ha = mock(HomeAssistantAdapter.class);
    private final HaServiceController controller = new HaServiceController(ha);

    private static HaServiceController.ServiceCall call(String domain, String service, String entity) {
        return new HaServiceController.ServiceCall(domain, service, entity, null);
    }

    @Test
    void onTheWayPreheatIsForwardedToTheCabinInstance() {
        when(ha.callService(any(), any(), any(), any(), any())).thenReturn(true);

        ResponseEntity<Map<String, Object>> res =
            controller.call(call("input_boolean", "turn_on", "input_boolean.on_the_way_to_cabin"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("accepted", true);
        verify(ha).callService("cabin", "input_boolean", "turn_on", "input_boolean.on_the_way_to_cabin", Map.of());
    }

    @Test
    void switchToggleIsForwarded() {
        when(ha.callService(any(), any(), any(), any(), any())).thenReturn(true);

        assertThat(controller.call(call("switch", "turn_off", "switch.heater_mech_room")).getStatusCode().value())
            .isEqualTo(200);
        verify(ha).callService("cabin", "switch", "turn_off", "switch.heater_mech_room", Map.of());
    }

    @Test
    void haFailureIsA502NotASilentSuccess() {
        when(ha.callService(any(), any(), any(), any(), any())).thenReturn(false);

        ResponseEntity<Map<String, Object>> res = controller.call(call("switch", "turn_on", "switch.heater_mech_room"));

        assertThat(res.getStatusCode().value()).isEqualTo(502);
        assertThat(res.getBody()).containsEntry("accepted", false);
    }

    @Test
    void anythingOutsideTheAllowlistIsRejectedWithoutCallingHa() {
        HaServiceController.ServiceCall[] rejected = {
            call("lock", "unlock", "lock.front_door"),                    // domain
            call("switch", "set_value", "switch.heater_mech_room"),       // service
            call("switch", "turn_on", "input_boolean.on_the_way_to_cabin"), // domain/entity mismatch
            call("switch", "turn_on", "switch.main_water_valve"),        // denied entity
            call("switch", "turn_on", "switch.Heater; rm -rf"),          // malformed
            call("switch", "turn_on", null),                             // missing
            new HaServiceController.ServiceCall("switch", "turn_on", "switch.heater_mech_room", "mars"),
        };
        for (HaServiceController.ServiceCall c : rejected) {
            assertThat(controller.call(c).getStatusCode().value()).as(String.valueOf(c)).isEqualTo(400);
        }
        verifyNoInteractions(ha);
    }
}
