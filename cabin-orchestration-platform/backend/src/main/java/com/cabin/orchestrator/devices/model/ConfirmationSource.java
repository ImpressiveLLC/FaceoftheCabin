package com.cabin.orchestrator.devices.model;

/**
 * D4's provenance mixin (docs/ontology/DECISIONS.md), applied to a D7
 * reporting relationship. Ordered by trust: a later, lower-priority source
 * must never downgrade an already-confirmed higher-priority fact (see
 * JdbcDeviceReportingRelationshipRepository.upsert()'s own comment).
 */
public enum ConfirmationSource {
    /** The older, sometimes-wrong DeviceType.telemetryFields() static per-type guess. Lowest trust; kept as a provisional fallback for a not-yet-observed device. */
    TYPE_INFERRED(1),
    /** CabinEventService.reportedFieldsByDevice() -- confirmed by real logged data. */
    EMPIRICAL_OBSERVATION(2),
    /** Zigbee2MqttAdapter.extractVendorReportedFields() -- confirmed by the device's own vendor spec (Z2M's exposes[]), zero network call. */
    VENDOR_SPEC(3),
    /** A person's explicit correction. Highest trust -- always wins. */
    MANUAL_OVERRIDE(4);

    private final int priority;

    ConfirmationSource(int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    /** Matches the lower_snake_case values docs/ontology/schema/device-reporting-relationship.schema.json declares. */
    public String dbValue() {
        return name().toLowerCase();
    }

    public static ConfirmationSource fromDbValue(String value) {
        return ConfirmationSource.valueOf(value.toUpperCase());
    }
}
