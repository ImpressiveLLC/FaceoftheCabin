package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;

/** Durable, person-authored portion of a registry entry. */
public record DeviceLifecycleRecord(
    DeviceDescriptor descriptor,
    DeviceLifecycleState lifecycleState,
    boolean configurationAsserted
) {}
