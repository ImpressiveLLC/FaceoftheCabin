package com.cabin.orchestrator.devices.model;

import java.time.Instant;

/**
 * D7 (docs/ontology/DECISIONS.md), Option B: one row per measurement type a
 * device actually reports, distinct from the device's own name/identity --
 * see docs/ontology/schema/device-reporting-relationship.schema.json for the
 * full reasoning this mirrors field-for-field.
 */
public record DeviceReportingRelationship(
    String deviceId,
    String semanticField,
    String measurementType,
    ConfirmationSource confirmationSource,
    Instant confirmedAt
) {}
