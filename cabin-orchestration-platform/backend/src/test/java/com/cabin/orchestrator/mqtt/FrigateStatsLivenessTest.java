package com.cabin.orchestrator.mqtt;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrigateStatsLivenessTest {

    @Test
    void idleMotionGatedCameraAtZeroFpsIsStillListed() {
        String stats = """
            {"cameras": {
               "cabin_outside_reolink": {"camera_fps": 5.1, "pid": 991},
               "driveway": {"camera_fps": 0.0, "process_fps": 5.1, "pid": 1114},
               "home_aldrich_front": {"camera_fps": 0.0, "pid": 1258}},
             "detectors": {"cpu1": {"inference_speed": 57.59}}}
            """;

        assertEquals(Set.of("cabin_outside_reolink", "driveway", "home_aldrich_front"),
            FrigateStatsLiveness.cameraNames(stats));
    }

    @Test
    void payloadsWithoutACamerasObjectYieldNothing() {
        assertTrue(FrigateStatsLiveness.cameraNames(null).isEmpty());
        assertTrue(FrigateStatsLiveness.cameraNames("").isEmpty());
        assertTrue(FrigateStatsLiveness.cameraNames("not json").isEmpty());
        assertTrue(FrigateStatsLiveness.cameraNames("{\"detectors\": {}}").isEmpty());
        assertTrue(FrigateStatsLiveness.cameraNames("{\"cameras\": null}").isEmpty());
        assertTrue(FrigateStatsLiveness.cameraNames("{\"cameras\": [\"driveway\"]}").isEmpty());
        assertTrue(FrigateStatsLiveness.cameraNames("[1,2]").isEmpty());
    }
}
