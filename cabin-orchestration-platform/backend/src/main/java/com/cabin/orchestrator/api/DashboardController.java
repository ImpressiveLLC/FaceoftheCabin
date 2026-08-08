package com.cabin.orchestrator.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
@CrossOrigin
public class DashboardController {
    private final String familyDashboardUrl;

    // Template configuration fields -- required entries for anyone cloning
    // this app as a new instance (see ROADMAP.md's "Template Configuration
    // Fields"). Both are @Value-injected rather than DB-backed, matching
    // this codebase's existing pattern for build/deploy-time template
    // config (e.g. CameraMediaController's blinkBridgeUrl) rather than
    // live-editable settings -- a new instance sets these once at
    // deployment, the Config panel just displays what was set.
    private final String platform;
    private final String remoteAccess;

    public DashboardController(
            @Value("${cabin.dashboard.familyDashboardUrl}") String familyDashboardUrl,
            @Value("${cabin.instance.platform:Not configured — set CABIN_INSTANCE_PLATFORM}") String platform,
            // Comma-separated; a fresh template clone with nothing set defaults
            // to Tailscale (the documented default remote-access path for a new
            // instance) rather than an empty/unconfigured-looking list.
            @Value("${cabin.instance.remoteAccess:Tailscale}") String remoteAccess) {
        this.familyDashboardUrl = familyDashboardUrl;
        this.platform = platform;
        this.remoteAccess = remoteAccess;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of(
            "familyDashboardUrl", familyDashboardUrl,
            "platformName", "Cabin Orchestration Platform",
            "platform", platform,
            "remoteAccess", remoteAccess
        );
    }
}
