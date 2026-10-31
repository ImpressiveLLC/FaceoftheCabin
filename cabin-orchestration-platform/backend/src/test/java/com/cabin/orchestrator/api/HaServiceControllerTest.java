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
        when(ha.callServiceOutcome(any(), any(), any(), any(), any())).thenReturn(HomeAssistantAdapter.ServiceOutcome.ACCEPTED);

        ResponseEntity<Map<String, Object>> res =
            controller.call(call("input_boolean", "turn_on", "input_boolean.on_the_way_to_cabin"));

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("accepted", true);
        verify(ha).callServiceOutcome("cabin", "input_boolean", "turn_on", "input_boolean.on_the_way_to_cabin", Map.of());
    }

    @Test
    void switchToggleIsForwarded() {
        when(ha.callServiceOutcome(any(), any(), any(), any(), any())).thenReturn(HomeAssistantAdapter.ServiceOutcome.ACCEPTED);

        assertThat(controller.call(call("switch", "turn_off", "switch.heater_mech_room")).getStatusCode().value())
            .isEqualTo(200);
        verify(ha).callServiceOutcome("cabin", "switch", "turn_off", "switch.heater_mech_room", Map.of());
    }

    @Test
    void haFailureIsA502NotASilentSuccess() {
        when(ha.callServiceOutcome(any(), any(), any(), any(), any())).thenReturn(HomeAssistantAdapter.ServiceOutcome.NOT_ACCEPTED);

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

    @Test
    void aReadTimeoutIsReportedAsOutcomeUnknownNotAsNotAccepted() {
        when(ha.callServiceOutcome(any(), any(), any(), any(), any())).thenReturn(HomeAssistantAdapter.ServiceOutcome.UNKNOWN);

        ResponseEntity<Map<String, Object>> res = controller.call(call("switch", "turn_on", "switch.heater_mech_room"));

        assertThat(res.getStatusCode().value()).isEqualTo(504);
        assertThat(res.getBody()).containsEntry("accepted", false).containsEntry("outcome", "unknown");
        assertThat(String.valueOf(res.getBody().get("message"))).contains("may or may not have been carried out");
    }

    @Test
    void anAcceptedCallAndARefusedCallSayTheirOutcome() {
        when(ha.callServiceOutcome(any(), any(), any(), any(), any())).thenReturn(HomeAssistantAdapter.ServiceOutcome.ACCEPTED);
        assertThat(controller.call(call("switch", "turn_on", "switch.heater_mech_room")).getBody()).containsEntry("outcome", "accepted");
        when(ha.callServiceOutcome(any(), any(), any(), any(), any())).thenReturn(HomeAssistantAdapter.ServiceOutcome.NOT_ACCEPTED);
        assertThat(controller.call(call("switch", "turn_on", "switch.heater_mech_room")).getBody()).containsEntry("outcome", "not_accepted");
    }
}
