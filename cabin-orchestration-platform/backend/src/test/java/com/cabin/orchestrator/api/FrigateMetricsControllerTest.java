package com.cabin.orchestrator.api;

import com.cabin.orchestrator.integrations.cameras.BlinkLiveviewService;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Covers parseCameraFps() against a real captured Prometheus response
 * (live query against this instance's own cabin-prometheus, 2026-08-08)
 * rather than a hand-written guess at the shape.
 */
class FrigateMetricsControllerTest {

    private final FrigateMetricsController controller = new FrigateMetricsController(blinkService());

    private static BlinkLiveviewService blinkService() {
        BlinkLiveviewService blink = mock(BlinkLiveviewService.class);
        when(blink.blinkCameraMap()).thenReturn(Map.of(
            "driveway", "Outdoor 4 - DHEE", "home_aldrich_front", "AldrichFront"));
        return blink;
    }

    // Real response from GET /api/v1/query?query=frigate_camera_fps
    // against this instance's live Prometheus, captured 2026-08-08.
    private static final String REAL_RESPONSE = """
        {"status":"success","data":{"resultType":"vector","result":[
          {"metric":{"__name__":"frigate_camera_fps","camera_name":"front_door","instance":"frigate:5000","job":"frigate"},"value":[1786211510.093,"0"]},
          {"metric":{"__name__":"frigate_camera_fps","camera_name":"driveway","instance":"frigate:5000","job":"frigate"},"value":[1786211510.093,"5.1"]}
        ]}}
        """;

    @Test
    @SuppressWarnings("unchecked")
    void parsesEachCameraFromARealPrometheusResponse() {
        Map<String, Object> result = controller.parseCameraFps(REAL_RESPONSE);

        assertEquals(2, result.size());
        assertEquals(0.0, ((Map<String, Object>) result.get("front_door")).get("cameraFps"));
        assertEquals(5.1, ((Map<String, Object>) result.get("driveway")).get("cameraFps"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void blinkRelayedCamerasAreFlaggedMotionGatedAndContinuousOnesAreNot() {
        // 2026-10-21: idle Blink cameras (0.0 fps) read "No signal" on the tile.
        String idleBlinkAndReolink = """
            {"status":"success","data":{"resultType":"vector","result":[
              {"metric":{"camera_name":"cabin_outside_reolink"},"value":[1786211510.093,"0"]},
              {"metric":{"camera_name":"driveway"},"value":[1786211510.093,"0"]},
              {"metric":{"camera_name":"home_aldrich_front"},"value":[1786211510.093,"0"]}
            ]}}
            """;

        Map<String, Object> result = controller.parseCameraFps(idleBlinkAndReolink);

        assertEquals(true, ((Map<String, Object>) result.get("driveway")).get("motionGated"));
        assertEquals(true, ((Map<String, Object>) result.get("home_aldrich_front")).get("motionGated"));
        assertEquals(false, ((Map<String, Object>) result.get("cabin_outside_reolink")).get("motionGated"),
            "a continuous camera at 0 fps must stay a real no-signal case");
    }

    @Test
    void emptyResultVectorProducesAnEmptyMapNotAnError() {
        Map<String, Object> result = controller.parseCameraFps(
            "{\"status\":\"success\",\"data\":{\"resultType\":\"vector\",\"result\":[]}}");

        assertTrue(result.isEmpty());
    }

    @Test
    void malformedJsonProducesAnEmptyMapRatherThanThrowing() {
        Map<String, Object> result = controller.parseCameraFps("not valid json at all");

        assertTrue(result.isEmpty(), "a Prometheus outage/malformed response must degrade gracefully, not 500");
    }

    @Test
    void aResultWithNoCameraNameLabelIsSkippedNotCrashing() {
        // e.g. frigate_detection_total_fps has no camera_name label at all
        String withoutCameraName = """
            {"status":"success","data":{"resultType":"vector","result":[
              {"metric":{"__name__":"frigate_detection_total_fps","instance":"frigate:5000","job":"frigate"},"value":[1786211510.093,"0"]}
            ]}}
            """;

        Map<String, Object> result = controller.parseCameraFps(withoutCameraName);

        assertTrue(result.isEmpty());
    }
}
