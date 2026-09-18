package com.cabin.orchestrator.alerts;

import java.time.Instant;

/** POST /api/alerts/acknowledgments request body. snoozedUntil is required when mode is SNOOZED, ignored (should be null) for IGNORED. */
public record AcknowledgeAlertRequest(String alertKey, String mode, Instant snoozedUntil) {}
