package com.cabin.orchestrator.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin
public class DashboardController {
    @Value("${cabin.dashboard.familyDashboardUrl}")
    private String familyDashboardUrl;

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "familyDashboardUrl", familyDashboardUrl,
            "platformName", "Cabin Orchestration Platform"
        );
    }
}
