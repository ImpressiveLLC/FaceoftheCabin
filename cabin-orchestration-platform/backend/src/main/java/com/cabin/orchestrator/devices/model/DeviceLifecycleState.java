package com.cabin.orchestrator.devices.model;

/**
 * Human-controlled relationship between a discovered device and this app.
 * Device health and enabled/disabled configuration are separate axes.
 */
public enum DeviceLifecycleState {
    /** Passively discovered; reviewing or closing the UI does not change it. */
    CANDIDATE,
    /** Explicitly accepted into scope, but not assigned/configured yet. */
    AVAILABLE,
    /** Assigned/configured for use by this app. */
    ASSIGNED,
    /** Kept for a later decision, outside the active device list. */
    DEFERRED,
    /** Deliberately excluded, retained only in the previously-exposed cache. */
    IGNORED;

    public boolean isInScope() {
        return this == AVAILABLE || this == ASSIGNED;
    }

    public boolean isPreviouslyExposed() {
        return this == DEFERRED || this == IGNORED;
    }

    public boolean allowsActiveUse() {
        return this == ASSIGNED;
    }
}
