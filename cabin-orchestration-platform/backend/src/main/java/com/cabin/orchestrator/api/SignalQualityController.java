package com.cabin.orchestrator.api;

import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PROTOTYPE, 2026-08-08 -- surfaces SignalQualityRegistry's per-device
 * Zigbee LQI trend/anomaly data for evaluation. Not wired into any real
 * alert path yet; see SignalQualityRegistry's own class comment for the
 * full reasoning. A device only appears here once it's sent at least one
 * message with a linkquality field since the backend last started.
 */
@RestController
@RequestMapping("/api/signal-quality")
@CrossOrigin
public class SignalQualityController {

    private final SignalQualityRegistry registry;

    public SignalQualityController(SignalQualityRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> get() {
        Map<String, Object> body = new LinkedHashMap<>();
        registry.allAssessments().forEach((deviceId, a) -> {
            // LinkedHashMap, not Map.of() -- baseline is legitimately null
            // (not enough samples yet), and Map.of() throws on a null
            // value rather than serializing it as JSON null.
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("current", a.current());
            entry.put("baseline", a.baseline());
            entry.put("anomalous", a.anomalous());
            entry.put("sampleCount", a.sampleCount());
            body.put(deviceId, entry);
        });
        return body;
    }
}
