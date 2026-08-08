package com.cabin.orchestrator.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the "Template Configuration Fields" this session added --
 * platform and remoteAccess are the two new fields a template clone must
 * set at deploy time (see ROADMAP.md), surfaced verbatim through
 * GET /api/dashboard/config so the Config panel can display them without
 * any extra plumbing.
 */
class DashboardControllerTest {

    @Test
    void surfacesConfiguredPlatformAndRemoteAccess() {
        DashboardController controller = new DashboardController(
            "https://family.example.com", "Self-hosted — Lenovo M920q", "Tailscale,Cloudflare Tunnel");

        Map<String, Object> config = controller.config();

        assertEquals("Self-hosted — Lenovo M920q", config.get("platform"));
        assertEquals("Tailscale,Cloudflare Tunnel", config.get("remoteAccess"));
        assertEquals("https://family.example.com", config.get("familyDashboardUrl"));
    }

    @Test
    void defaultsGuideATemplateCloneTowardConfiguringThem() {
        // Matches @Value's default expressions in the real constructor
        // annotations -- a fresh clone with nothing set yet should read as
        // "needs setup", not silently look like a real value, except for
        // remoteAccess which intentionally defaults to Tailscale (this
        // project's documented default remote-access path for a new
        // instance) rather than an empty/unconfigured-looking string.
        DashboardController controller = new DashboardController(
            "https://family.example.com",
            "Not configured — set CABIN_INSTANCE_PLATFORM",
            "Tailscale");

        Map<String, Object> config = controller.config();

        assertEquals("Not configured — set CABIN_INSTANCE_PLATFORM", config.get("platform"));
        assertEquals("Tailscale", config.get("remoteAccess"));
    }
}
