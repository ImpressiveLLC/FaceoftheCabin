package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Gates command-kind workflow actions before DeviceRegistry.sendCommand() is
 * called -- closes the free-text/ungated gap DeviceRegistry itself doesn't
 * check (any caller can pass any command string today, DeviceController's
 * own sendCommand endpoint included). Scoped to the new workflow engine
 * only; retrofitting DeviceController's human-driven endpoint behind this
 * same gate is a reasonable follow-up, deliberately not done here -- a
 * person clicking a button live is a different trust model than an
 * unattended background rule.
 *
 * v1 (2026-08-14): validates device existence/enabled/COMMAND-capability
 * only. Validating the specific requested command against an
 * ontology-derived action_definition vocabulary is real follow-up work
 * once that vocabulary exists (Codex's parallel slice) -- not built here.
 */
@Service
public class CommandCatalogService {

    private final DeviceRegistry registry;

    public CommandCatalogService(DeviceRegistry registry) {
        this.registry = registry;
    }

    public boolean isCommandAllowed(String deviceId) {
        Optional<DeviceDescriptor> descriptor = registry.descriptor(deviceId);
        return descriptor.isPresent()
            && descriptor.get().enabled()
            && descriptor.get().capabilities().contains(DeviceCapability.COMMAND);
    }
}
