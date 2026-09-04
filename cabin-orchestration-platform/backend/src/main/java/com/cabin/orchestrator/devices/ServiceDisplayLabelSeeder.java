package com.cabin.orchestrator.devices;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * D13 (Service-Level Data Lineage, docs/ontology/DECISIONS.md): seeds
 * display_label for the cabin's real, currently-confirmed service entities --
 * the human-readable names Bug #4 (2026-09 bug sprint) surfaces in place of
 * the raw device_id. Verified against the live M920q device_reporting_relationship
 * table, not guessed: temp_kitchen/temp_mech_room are native Zigbee combo
 * sensors (humidity + temperature on one device_id); the Kidde unit's CO,
 * temperature, and humidity services are each their own separate HA entity
 * (device_id), per D13's own "several ungrouped HA entities" note.
 *
 * setDisplayLabel() only ever attaches to a row that already exists (see its
 * own javadoc) -- the Kidde CO row in particular won't exist until the
 * co-level entity is correctly classified as CO_SENSOR (Bug #3) and a
 * telemetry poll has confirmed it at least once. Until then this entry is a
 * harmless no-op, not an error.
 *
 * Seeded only when no label is set yet, mirroring CredentialPointerSeeder's
 * own "never clobber a later human edit" rule -- an admin who curates a
 * different label via PATCH /api/devices/{id}/reporting/{field}/display-label
 * must never have that edit silently reverted on the next restart.
 */
@Component
public class ServiceDisplayLabelSeeder {

    private record Seed(String deviceId, String semanticField, String displayLabel) {}

    private static final List<Seed> SEEDS = List.of(
        new Seed("z2m-temp_kitchen", "humidity", "Kitchen Humidity"),
        new Seed("z2m-temp_kitchen", "temperature", "Kitchen Temperature"),
        new Seed("z2m-temp_mech_room", "humidity", "Mech Room Humidity"),
        new Seed("z2m-temp_mech_room", "temperature", "Mech Room Temperature"),
        new Seed("ha-cabin-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-co-level", "co", "Upstairs CO"),
        new Seed("ha-cabin-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-indoor-temperature", "temperature", "Upstairs Temperature"),
        new Seed("ha-cabin-sensor-living-room-kidde-co-temp-and-humidity-cabin-upstairs-humidity", "humidity", "Upstairs Humidity")
    );

    private final DeviceReportingRelationshipRepository repository;

    public ServiceDisplayLabelSeeder(DeviceReportingRelationshipRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void seedIfMissing() {
        for (Seed seed : SEEDS) {
            boolean alreadyLabeled = repository.findByDevice(seed.deviceId()).stream()
                .anyMatch(r -> r.semanticField().equals(seed.semanticField())
                    && r.displayLabel() != null && !r.displayLabel().isBlank());
            if (!alreadyLabeled) {
                repository.setDisplayLabel(seed.deviceId(), seed.semanticField(), seed.displayLabel());
            }
        }
    }
}
