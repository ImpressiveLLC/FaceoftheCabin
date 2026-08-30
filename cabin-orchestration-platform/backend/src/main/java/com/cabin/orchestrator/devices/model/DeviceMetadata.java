package com.cabin.orchestrator.devices.model;

import java.time.Instant;

/**
 * D1/D6/D7 real-column device facts (docs/ontology/DECISIONS.md, issue #30)
 * plus the D4 generic provenance mixin (issue #33) -- deliberately separate
 * from DeviceLifecycleRecord, which owns the human-editable config
 * (location, enabled, room) this record does not touch. A device can exist
 * with no DeviceMetadata at all; that just means nothing has discovered or
 * asserted any of these facts yet, not an error state.
 */
public record DeviceMetadata(
    String manufacturer,
    String model,
    String area,
    Instant pairedAt,
    String createdBy,
    Instant createdAt,
    String modifiedBy,
    Instant modifiedAt,
    int version
) {}
