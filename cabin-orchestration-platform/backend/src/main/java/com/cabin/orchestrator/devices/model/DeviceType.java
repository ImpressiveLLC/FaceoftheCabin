package com.cabin.orchestrator.devices.model;

/**
 * category() (added 2026-08-19) formalizes the groupings this enum's
 * constants already carried as plain comments -- same taxonomy, just a
 * real field instead of text only a reader already knows to look at. See
 * DeviceCategory's own comment for why this exists.
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
}
