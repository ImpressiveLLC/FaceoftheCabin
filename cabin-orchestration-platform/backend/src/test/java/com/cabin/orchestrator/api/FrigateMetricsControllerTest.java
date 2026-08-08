package com.cabin.orchestrator.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers parseCameraFps() against a real captured Prometheus response
 * (live query against this instance's own cabin-prometheus, 2026-08-08)
 * rather than a hand-written guess at the shape.
 */
class FrigateMetricsControllerTest {

    private final FrigateMetricsController controller = new FrigateMetricsController();

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
