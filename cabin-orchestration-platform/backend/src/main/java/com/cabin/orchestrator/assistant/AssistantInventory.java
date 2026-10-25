package com.cabin.orchestrator.assistant;

import java.time.Instant;
import java.util.List;

/**
 * W-67 slice 1 (D27 R-OC-2, HO-2026-10-16 step 1): the permission-filtered,
 * non-secret projection of what the Ask entity resolver may target.
 *
 * <p>{@code version} is a hash of everything plan-relevant in {@code entries}
 * (identity, site, capabilities, availability), deliberately excluding
 * {@code lastObserved}, which changes on every telemetry message. A confirmed
 * plan bound to a version is therefore invalidated by an inventory change
 * (D27 Q-OC-10) without being invalidated by a routine sensor reading.
 */
public record AssistantInventory(String version, Instant generatedAt, List<Entry> entries) {

    /**
     * One addressable device. Never carries credentials, tokens, IP or MAC
     * addresses, MQTT topics or Home Assistant URLs: those live on
     * DeviceDescriptor.connectionString and in the status attribute bag, and
     * this record is built field by field, never by copying either.
     */
    public record Entry(
        String entityId,            // canonical, D1: the device id, never renamed
        String name,                // display name
        List<String> aliases,       // other names already stored for this device
        String site,                // D6/D24 location: "cabin" | "home"
        String area,                // DeviceMetadata.area, admin-curated; null when unknown
        String room,                // person-set room; null when unknown
        String deviceClass,         // DeviceType name
        String category,            // DeviceCategory name
        List<String> capabilities,  // DeviceCapability names, sorted
        String lifecycle,           // AVAILABLE | ASSIGNED
        boolean enabled,
        String availability,        // ONLINE | OFFLINE | ALARM | UNKNOWN
        Instant lastObserved
    ) {}
}
