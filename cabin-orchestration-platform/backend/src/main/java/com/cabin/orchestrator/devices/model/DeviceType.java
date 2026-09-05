package com.cabin.orchestrator.devices.model;

import java.util.Set;

/**
 * category() (added 2026-08-19) formalizes the groupings this enum's
 * constants already carried as plain comments -- same taxonomy, just a
 * real field instead of text only a reader already knows to look at. See
 * DeviceCategory's own comment for why this exists.
 *
 * telemetryFields() (added 2026-08-27) closes a related gap the same way:
 * "which payload fields can a device of this type actually report" used to
 * exist only as an unlabeled, frontend-only fact baked into App.jsx's
 * SENSOR_FIELD_OPTIONS types arrays (e.g. it already knew a
 * TEMPERATURE_SENSOR can report humidity too, since a Zigbee combo sensor
 * is one entity for both readings) -- correct, but invisible everywhere
 * else: Device Manager's own device-detail view showed only the abstract
 * DeviceCapability chips (COMMAND/POWER_MONITOR/TELEMETRY), never the
 * actual field list, and a null-appliesToDeviceType trigger (e.g.
 * trigger_mold_risk_detected, which by design spans both
 * TEMPERATURE_SENSOR and HUMIDITY_SENSOR) had no way to scope its
 * device picker to "reports humidity" and fell back to showing every
 * device of every type. This is now the one canonical place that fact
 * lives -- DeviceRegistry.withOntologyMetadata() serializes it onto every
 * DeviceStatus as attrs.reportsFields the same way it already derives
 * category(), and TriggerVocabularyEntry.appliesToField reads it to scope
 * the workflow-creation device picker by field instead of by type.
 */
public enum DeviceType {
    // Safety
    SMOKE_ALARM(DeviceCategory.SAFETY),
    CO_ALARM(DeviceCategory.SAFETY),
    WATER_LEAK_SENSOR(DeviceCategory.SAFETY),
    // Security
    CAMERA(DeviceCategory.SECURITY),
    LOCK(DeviceCategory.SECURITY),
    MOTION_SENSOR(DeviceCategory.SECURITY),
    CONTACT_SENSOR(DeviceCategory.SECURITY),
    // Climate
    THERMOSTAT(DeviceCategory.CLIMATE),
    TEMPERATURE_SENSOR(DeviceCategory.CLIMATE),
    HUMIDITY_SENSOR(DeviceCategory.CLIMATE),
    // Added 2026-08-27 for Kidde's HA-discovered air-quality entities (see
    // HomeAssistantDiscoveryService.inferType()) -- continuous ambient
    // readings, same category as temperature/humidity, distinct from
    // CO_ALARM (a binary safety trigger, not a chartable numeric level).
    CO2_SENSOR(DeviceCategory.CLIMATE),
    AIR_QUALITY_SENSOR(DeviceCategory.CLIMATE),
    CO_SENSOR(DeviceCategory.CLIMATE),
    // Utilities
    WATER_PRESSURE_SENSOR(DeviceCategory.UTILITIES),
    POWER_METER(DeviceCategory.UTILITIES),
    // Appliances
    DISHWASHER(DeviceCategory.APPLIANCES),
    WASHING_MACHINE(DeviceCategory.APPLIANCES),
    DRYER(DeviceCategory.APPLIANCES),
    // Network
    ROUTER(DeviceCategory.NETWORK),
    UPS(DeviceCategory.NETWORK),
    // Platform
    GOOGLE_HOME_DEVICE(DeviceCategory.PLATFORM),
    HOME_ASSISTANT_ENTITY(DeviceCategory.PLATFORM),
    DASHBOARD(DeviceCategory.PLATFORM);

    private final DeviceCategory category;

    DeviceType(DeviceCategory category) {
        this.category = category;
    }

    public DeviceCategory category() {
        return category;
    }

    /**
     * Which CabinEvent payload fields a device of this type can actually
     * report -- see this class's own doc for why this exists. Mirrors
     * App.jsx's SENSOR_FIELD_OPTIONS types arrays exactly (kept in sync by
     * hand, same discipline as everything else in this taxonomy); a type
     * not listed here reports no chartable numeric telemetry field.
     */
    public Set<String> telemetryFields() {
        return switch (this) {
            case TEMPERATURE_SENSOR -> Set.of("temperature", "humidity");
            case HUMIDITY_SENSOR -> Set.of("humidity");
            case CO2_SENSOR -> Set.of("co2");
            case AIR_QUALITY_SENSOR -> Set.of("airQualityIndex");
            case CO_SENSOR -> Set.of("co");
            // D15 (Energy Device Ontology, 2026-09-05).
            case POWER_METER -> Set.of("power", "energy", "current", "voltage");
            default -> Set.of();
        };
    }
}
