package com.cabin.orchestrator.devices.model;

public enum DeviceType {
    // Safety
    SMOKE_ALARM, CO_ALARM, WATER_LEAK_SENSOR,
    // Security
    CAMERA, LOCK, MOTION_SENSOR, CONTACT_SENSOR,
    // Climate
    THERMOSTAT, TEMPERATURE_SENSOR, HUMIDITY_SENSOR,
    // Utilities
    WATER_PRESSURE_SENSOR, POWER_METER,
    // Appliances
    DISHWASHER, WASHING_MACHINE, DRYER,
    // Network
    ROUTER, UPS,
    // Platform
    GOOGLE_HOME_DEVICE, HOME_ASSISTANT_ENTITY, DASHBOARD
}
