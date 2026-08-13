package com.cabin.orchestrator.devices.model;

import java.util.Locale;

/** Explicit person-authored decisions available from Device Manager review. */
public enum DeviceLifecycleAction {
    ACCEPT,
    DEFER,
    IGNORE,
    REVIEW;

    public static DeviceLifecycleAction from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A lifecycle action is required");
        }
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }

    public DeviceLifecycleState targetState() {
        return switch (this) {
            case ACCEPT -> DeviceLifecycleState.AVAILABLE;
            case DEFER -> DeviceLifecycleState.DEFERRED;
            case IGNORE -> DeviceLifecycleState.IGNORED;
            case REVIEW -> DeviceLifecycleState.CANDIDATE;
        };
    }
}
