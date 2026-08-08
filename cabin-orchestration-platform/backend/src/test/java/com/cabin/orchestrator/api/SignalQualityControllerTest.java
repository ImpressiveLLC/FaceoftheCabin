package com.cabin.orchestrator.api;

import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignalQualityControllerTest {

    @SuppressWarnings("unchecked")
    @Test
    void returnsCurrentValueAndNullBaselineWithTooFewSamples() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        registry.record("z2m-motion_entry", 200);
        SignalQualityController controller = new SignalQualityController(registry);

        Map<String, Object> body = controller.get();

        Map<String, Object> entry = (Map<String, Object>) body.get("z2m-motion_entry");
        assertEquals(200, entry.get("current"));
        assertNull(entry.get("baseline"), "too few samples must serialize as JSON null, not throw or default to 0");
        assertEquals(false, entry.get("anomalous"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void returnsANumericBaselineOnceEnoughSamplesExist() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < 6; i++) registry.record("z2m-motion_entry", 200);
        SignalQualityController controller = new SignalQualityController(registry);

        Map<String, Object> entry = (Map<String, Object>) controller.get().get("z2m-motion_entry");

        assertEquals(200.0, entry.get("baseline"));
    }

    @Test
    void omitsDevicesThatHaveNeverReportedLinkquality() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        SignalQualityController controller = new SignalQualityController(registry);

        assertTrue(controller.get().isEmpty());
    }
}
