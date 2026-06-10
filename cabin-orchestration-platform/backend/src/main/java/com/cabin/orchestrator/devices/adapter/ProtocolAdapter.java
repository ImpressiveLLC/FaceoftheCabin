package com.cabin.orchestrator.devices.adapter;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;

import java.util.Optional;

/**
 * Implemented by each integration layer.
 * The DeviceRegistry dispatches to the correct adapter based on
 * DeviceDescriptor.protocolAdapter().
 */
public interface ProtocolAdapter {

    /** Identifier matching DeviceDescriptor.protocolAdapter() — e.g. "ha_rest" */
    String adapterType();

    /** Poll or query current state. Return empty if the device is unreachable. */
    Optional<DeviceStatus> fetchState(DeviceDescriptor descriptor);

    /** Send a command. Returns true if acknowledged. */
    boolean sendCommand(DeviceDescriptor descriptor, String command, Object payload);
}
