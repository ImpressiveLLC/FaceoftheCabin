package com.cabin.orchestrator.devices.model;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Result of a self-discovery lookup for a device -- never written to the
 * live `device` table directly. A person must explicitly select fields and
 * call POST .../discovery/apply (DeviceRegistry.saveConfiguration() or
 * replaceConfiguration()) before anything here affects the real device.
 */
public record DeviceDiscoveryResult(
    String runId,
    String deviceId,
    Instant requestedAt,
    Instant appliedAt, // null until an apply call marks this run as used
    List<Match> matches
) {
    /** One candidate identification for the device, with full source provenance. */
    public record Match(
        String summary,
        String confidence, // "high" | "medium" | "low"
        DeviceType suggestedType,
        Set<DeviceCapability> suggestedCapabilities,
        InstallGuide installGuide,
        List<Source> sources
    ) {}

    /** mode: "extract" (verbatim excerpt), "summary" (synthesized), or "linkonly" (no usable text found). */
    public record InstallGuide(String mode, String content) {}

    /** Provenance for a claim: where it came from, not just what it says. */
    public record Source(String url, String title, String snippet, Instant fetchedAt) {}
}
