package com.cabin.orchestrator.integrations.kidde;

import org.springframework.stereotype.Service;

/**
 * Placeholder for Kidde Wi-Fi smoke alarm integration.
 *
 * Many consumer smoke alarms do not expose a simple local API. Best options:
 * - Home Assistant integration if supported
 * - Email/SMS alert parsing
 * - IFTTT/webhook bridge if available
 * - Vendor cloud API if documented
 */
@Service
public class KiddeIntegration {
    public void pollAlarmState() {
        // TODO: integrate through supported bridge.
    }
}
