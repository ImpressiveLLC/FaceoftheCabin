package com.cabin.orchestrator.devices.model;

/**
 * Capabilities a device may expose.
 * A device registers with 1..N capabilities; the UI and rules engine
 * use these to decide which controls and data panels to show.
 */
public enum DeviceCapability {
    TELEMETRY,       // pushes time-series metrics (PSI, temp, humidity…)
    COMMAND,         // accepts commands (set temp, lock/unlock…)
    STREAM,          // provides a video/audio stream (RTSP, WebRTC)
    ALARM,           // fires safety alerts (smoke, CO, water…)
    PRESENCE,        // reports presence / occupancy
    CLIMATE,         // HVAC-class control (setpoint, mode, fan)
    ACCESS_CONTROL,  // lock / unlock / status
    APPLIANCE,       // non-HVAC smart appliance (washer, dishwasher…)
    POWER_MONITOR    // energy metering
}
