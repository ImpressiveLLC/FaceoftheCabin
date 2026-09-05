package com.cabin.orchestrator.devices.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * D7 (docs/ontology/DECISIONS.md), Option B: one row per measurement type a
 * device actually reports, distinct from the device's own name/identity --
 * see docs/ontology/schema/device-reporting-relationship.schema.json for the
 * full reasoning this mirrors field-for-field.
 *
 * displayLabel is D13's "Service Entity" concept (docs/ontology/DECISIONS.md):
 * this exact (deviceId, semanticField) pair already IS D13's atomic service
 * entity, just missing the one field D13 adds -- a human-readable label for
 * this specific measurement, set only at curation time, never auto-derived
 * from the device's own name (see JdbcDeviceReportingRelationshipRepository's
 * upsert(), which deliberately never touches this column). Null until a
 * person explicitly sets one.
 */
public record DeviceReportingRelationship(
    String deviceId,
    String semanticField,
    String measurementType,
    ConfirmationSource confirmationSource,
    Instant confirmedAt,
    String displayLabel
) {
    /** Convenience constructor for every pre-D13 call site -- displayLabel defaults to unset. */
    public DeviceReportingRelationship(String deviceId, String semanticField, String measurementType,
                                         ConfirmationSource confirmationSource, Instant confirmedAt) {
        this(deviceId, semanticField, measurementType, confirmationSource, confirmedAt, null);
    }

    /**
     * D16 (Reporting Topics IA, Sprint 4): which reporting Topic this
     * Service Entity feeds, per ReportingTopics.topicFor(). Deliberately
     * computed here, not a stored column -- it's a pure function of
     * measurementType under D16's current 5-topic scheme, so persisting it
     * separately would just be a second copy of the same fact that could
     * drift. Null (not present as a UI-facing gap) when this measurement_type
     * has no Topic assignment yet -- see ReportingTopics' own doc for which
     * ones that's true of today.
     */
    @JsonProperty("reportsTo")
    public String reportsTo() {
        return ReportingTopics.topicFor(measurementType).orElse(null);
    }
}
