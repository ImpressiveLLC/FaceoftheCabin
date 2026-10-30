package com.cabin.orchestrator.api;

import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class DiscoveryLookupAttributesTest {

    private static DeviceStatus status(String id, Map<String, Object> attributes) {
        return new DeviceStatus(id, DeviceType.TEMPERATURE_SENSOR, id, "ONLINE", Instant.now(), attributes, "cabin");
    }

    private static final Map<String, DeviceStatus> DEVICES = Map.of(
        "z2m-temp_kitchen", status("z2m-temp_kitchen", Map.of("vendor", "SONOFF", "model", "SNZB-02WD")),
        "ha-bare", status("ha-bare", Map.of("discoveredFrom", "Home Assistant", "parentDeviceId", "z2m-temp_kitchen")));

    @Test
    void anEntityServiceSearchesAsTheZigbeeDeviceItBelongsTo() {
        Map<String, Object> entity = Map.of("discoveredFrom", "Home Assistant", "parentDeviceId", "ha-bare",
            "description", "temp_kitchen Battery (battery)");
        Map<String, Object> lookup = DeviceDiscoveryController.lookupAttributes(entity, DEVICES::get);
        assertEquals("SONOFF", lookup.get("vendor"));
        assertEquals("SNZB-02WD", lookup.get("model"));
    }

    @Test
    void anEntityWithNoIdentifiedAncestorSendsNoDescriptionSoNothingIsSearched() {
        Map<String, Object> entity = Map.of("discoveredFrom", "Home Assistant", "description", "Sun Next dusk");
        assertEquals("", DeviceDiscoveryController.lookupAttributes(entity, DEVICES::get).get("description"));
    }

    @Test
    void aDeviceWithItsOwnVendorOrADescriptionOnlyScanDeviceIsUnchanged() {
        Map<String, Object> own = Map.of("discoveredFrom", "Zigbee2MQTT bridge/devices", "vendor", "SONOFF");
        assertSame(own, DeviceDiscoveryController.lookupAttributes(own, DEVICES::get));
        Map<String, Object> scan = Map.of("discoveredFrom", "network scan", "description", "LG webOS TV OLED42C5PUA");
        assertSame(scan, DeviceDiscoveryController.lookupAttributes(scan, DEVICES::get));
    }
}
