package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** descriptorsByConnectionString finds every device sharing a target whatever the letter case, because the command guard relies on it. */
class DeviceRegistryConnectionLookupTest {

    private static DeviceDescriptor d(String id, String connection, String adapter) {
        return new DeviceDescriptor(id, id, DeviceType.HOME_ASSISTANT_ENTITY, Set.of(), adapter, connection, true, "cabin");
    }

    @Test
    void theLookupIgnoresLetterCaseAndFindsEveryAdapter() {
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.registerDescriptor(d("a", "switch.Shared_X", "ha_rest"));
        registry.registerDescriptor(d("b", "SWITCH.shared_x", "mqtt"));
        registry.registerDescriptor(d("c", "switch.other", "ha_rest"));

        assertEquals(Set.of("a", "b"), registry.descriptorsByConnectionString("switch.shared_x").stream().map(DeviceDescriptor::deviceId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2, registry.descriptorsByConnectionString("SWITCH.SHARED_X").size());
        assertTrue(registry.descriptorsByConnectionString(null).isEmpty());
        assertTrue(registry.descriptorsByConnectionString("switch.missing").isEmpty());
    }
}
