package com.cabin.orchestrator.integrations.cameras;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class BlinkLiveviewServiceTest {

    @Test
    void parsesMultipleCommaSeparatedPairs() {
        BlinkLiveviewService service = new BlinkLiveviewService();
        ReflectionTestUtils.setField(service, "blinkCameraMapRaw",
            "driveway:Outdoor 4 - DHEE,home_aldrich_front:AldrichFront");

        var map = service.blinkCameraMap();

        assertEquals("Outdoor 4 - DHEE", map.get("driveway"));
        assertEquals("AldrichFront", map.get("home_aldrich_front"));
        assertEquals(2, map.size());
    }

    @Test
    void returnsAnEmptyMapWhenUnset() {
        BlinkLiveviewService service = new BlinkLiveviewService();
        ReflectionTestUtils.setField(service, "blinkCameraMapRaw", "");

        assertTrue(service.blinkCameraMap().isEmpty());
    }

    @Test
    void startIsASkippedNoOpForACameraNotInTheMap() {
        BlinkLiveviewService service = new BlinkLiveviewService();
        ReflectionTestUtils.setField(service, "blinkCameraMapRaw", "driveway:Outdoor 4 - DHEE");

        BlinkLiveviewService.Result result = service.start("front_door");

        assertTrue(result.skipped(), "a camera not on the Blink relay path (e.g. the native-RTSP Reolink) must be a harmless no-op");
        assertTrue(result.ok());
    }
}
