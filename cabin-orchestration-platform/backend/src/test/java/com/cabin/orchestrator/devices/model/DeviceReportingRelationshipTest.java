package com.cabin.orchestrator.devices.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

// D16 (Reporting Topics IA, Sprint 4): reportsTo() is a derived method, not
// a record component -- confirms it actually reaches JSON (via its explicit
// @JsonProperty) rather than relying on records' default component-only
// serialization, which would silently drop a non-canonical accessor.
class DeviceReportingRelationshipTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void reportsToResolvesFromMeasurementType() {
        var powerReading = new DeviceReportingRelationship("z2m-heater_mech_room", "power", "power",
            ConfirmationSource.VENDOR_SPEC, Instant.now());
        assertEquals("energy", powerReading.reportsTo());

        var humidityReading = new DeviceReportingRelationship("z2m-temp_kitchen", "humidity", "humidity",
            ConfirmationSource.VENDOR_SPEC, Instant.now());
        assertEquals("comfort_air", humidityReading.reportsTo());
    }

    @Test
    void reportsToIsNullForAMeasurementTypeWithNoTopicYet() {
        var pressureReading = new DeviceReportingRelationship("z2m-somewhere", "pressure", "pressure",
            ConfirmationSource.VENDOR_SPEC, Instant.now());
        assertNull(pressureReading.reportsTo());
    }

    @Test
    void reportsToActuallySerializesToJson() throws Exception {
        var powerReading = new DeviceReportingRelationship("z2m-heater_mech_room", "power", "power",
            ConfirmationSource.VENDOR_SPEC, Instant.now());

        JsonNode json = mapper.readTree(mapper.writeValueAsString(powerReading));

        assertEquals("energy", json.get("reportsTo").asText());
        assertEquals("z2m-heater_mech_room", json.get("deviceId").asText());
    }
}
