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
        String suggestedName,
        DeviceType suggestedType,
        Set<DeviceCapability> suggestedCapabilities,
        // D7 (docs/ontology/DECISIONS.md): specific measurement-type names
        // this device reports (e.g. "temperature", "humidity"), distinct
        // from suggestedCapabilities' broader DeviceCapability buckets --
        // added 2026-08-29 alongside the LocalCatalogProvider path in
        // cabin-discovery, which populates this from Z2M's own exposes[]
        // when available. Empty for a match that doesn't have this data,
        // e.g. the Anthropic-backed path, which doesn't populate it yet.
        List<String> suggestedReportedFields,
        // D4's provenance mixin, applied to suggestedReportedFields
        // specifically -- "vendor_spec" for the LocalCatalogProvider (Z2M's
        // own exposes[]), null for a match that doesn't carry this kind of
        // claim at all (not every Match makes a reporting-relationship
        // claim, so this isn't defaulted to a value implying one).
        String suggestedReportedFieldsSource,
        InstallGuide installGuide,
        List<Source> sources
    ) {}

    /** mode: "extract" (verbatim excerpt), "summary" (synthesized), or "linkonly" (no usable text found). */
    public record InstallGuide(String mode, String content) {}

    /** Provenance for a claim: where it came from, not just what it says. */
    public record Source(String url, String title, String snippet, Instant fetchedAt) {}
}
