package com.cabin.orchestrator.integrations.google;

import org.springframework.stereotype.Service;

/**
 * Placeholder for Google Home / Google Device Access or Home Assistant bridge.
 *
 * Practical recommendation:
 * - Use Home Assistant as the actual Google-compatible device bridge.
 * - This service should call Home Assistant REST/WebSocket APIs instead of trying
 *   to directly control Google Home devices where APIs are limited.
 */
@Service
public class GoogleHomeIntegration {
    public void syncLocksAndCompatibleDevices() {
        // TODO: call Home Assistant API or Google SDM API where supported.
    }
}
