package com.cabin.orchestrator.presence;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * D11's "minimal aggregate presence assertion" — the only presence shape
 * ever allowed to cross the Family Hub / cabin-orchestration boundary.
 * No named individual, no precise location, no raw sensor value: this is
 * deliberately a strict subset of what PresenceService/PresenceSignalRegistry
 * track internally (which does carry a personId), never those types
 * themselves. See GET /api/presence/contract in PresenceController.
 *
 * "No new table, no new persistence" per the r6 handover -- this is a
 * point-in-time read of PresenceSignalRegistry's live in-memory signals,
 * not a stored fact. A manually-set profile (PresenceService.set(), the
 * toolbar override) always resolves to unknown() here: unlike a real
 * signal, a manual PUT has no timestamp to evaluate freshness/expiry
 * against, and fabricating one would mean either fabricating a confidence
 * this contract doesn't have grounds for, or adding persistence the
 * handover explicitly says not to add. Erring toward unknown matches D11's
 * own conservative bias (never over-assert presence).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresenceContractV1(
    @JsonProperty("state") String state,
    @JsonProperty("confidence") double confidence,
    @JsonProperty("observed_at") String observedAt,
    @JsonProperty("expires_at") String expiresAt,
    @JsonProperty("sources") List<String> sources,
    @JsonProperty("policy_version") String policyVersion
) {
    public static final String POLICY_VERSION = "1";

    /** Default expiry window per the r6 spec: observed_at + 30 minutes. */
    static final Duration FRESHNESS_WINDOW = Duration.ofMinutes(30);

    public static PresenceContractV1 unknown() {
        return new PresenceContractV1("unknown", 0.0, null, null, List.of(), POLICY_VERSION);
    }

    /**
     * Real signal-derived assertion, only when autoDerived is true (a
     * manual profile has no timestamp to check freshness against -- see
     * class javadoc) and at least one signal is fresher than the 30-minute
     * window. confidence is a binary 1.0/0.0, not a real probability --
     * the underlying signals (WiFi ARP check, GPS zone crossing) are
     * themselves binary detections with no confidence score of their own,
     * so synthesizing a fractional value would fabricate precision that
     * doesn't exist upstream.
     */
    public static PresenceContractV1 of(boolean autoDerived, List<PresenceSignalRegistry.Signal> signals, Instant now) {
        if (!autoDerived || signals.isEmpty()) {
            return unknown();
        }
        Instant observedAt = signals.stream()
            .map(PresenceSignalRegistry.Signal::lastUpdated)
            .max(Instant::compareTo)
            .orElse(null);
        if (observedAt == null || observedAt.isBefore(now.minus(FRESHNESS_WINDOW))) {
            return unknown();
        }
        boolean anyonePresent = signals.stream().anyMatch(PresenceSignalRegistry.Signal::present);
        List<String> sources = signals.stream()
            .map(PresenceContractV1::coarseSource)
            .distinct()
            .sorted()
            .toList();
        Instant expiresAt = observedAt.plus(FRESHNESS_WINDOW);
        return new PresenceContractV1(anyonePresent ? "home" : "away", 1.0,
            observedAt.toString(), expiresAt.toString(), sources, POLICY_VERSION);
    }

    /**
     * cabin/presence/* signals come from a WiFi ARP check
     * (automation_cabin_security_publish_nate_presence_from_phone);
     * home/presence/* signals come from an HA Companion App GPS zone
     * crossing (automation_home_presence_publish_nate_presence_from_phone_gps)
     * -- see docs/ontology.yaml. These are the only two mechanisms this
     * instance actually has; "location" is the one field already on
     * Signal that tells them apart, so no new field is needed to report
     * a coarse, honest source class instead of guessing (e.g. the
     * handover's own "motion_sensor" example doesn't apply here at all).
     */
    private static String coarseSource(PresenceSignalRegistry.Signal signal) {
        return "cabin".equals(signal.location()) ? "wifi_presence" : "phone_gps";
    }
}
