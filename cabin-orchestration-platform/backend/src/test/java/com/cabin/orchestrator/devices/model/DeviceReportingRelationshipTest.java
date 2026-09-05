package com.cabin.orchestrator.devices.model;

import com.cabin.orchestrator.api.DeviceController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

// D16 (Reporting Topics IA), Cowork ratification 2026-09-05: reportsTo
// moved off this record entirely (see its own class doc for why -- it
// needs the reporting device's DeviceType, which this record doesn't
// carry) and onto DeviceController.ReportingRelationshipView, computed at
// read time. This confirms the view's @JsonUnwrapped flattening still
// reaches JSON as a flat sibling field, matching the shape this endpoint's
// consumers already depend on.
class DeviceReportingRelationshipTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void reportingRelationshipViewFlattensReportsToAsASiblingField() throws Exception {
        var powerReading = new DeviceReportingRelationship("z2m-heater_mech_room", "power", "power",
            ConfirmationSource.VENDOR_SPEC, Instant.now());
        var view = new DeviceController.ReportingRelationshipView(powerReading, "energy");

        JsonNode json = mapper.readTree(mapper.writeValueAsString(view));

        assertEquals("energy", json.get("reportsTo").asText());
        assertEquals("z2m-heater_mech_room", json.get("deviceId").asText());
        assertEquals("power", json.get("measurementType").asText());
    }

    @Test
    void reportingRelationshipViewSerializesReportsToAsNullWhenAbsent() throws Exception {
        var pressureReading = new DeviceReportingRelationship("z2m-somewhere", "pressure", "pressure",
            ConfirmationSource.VENDOR_SPEC, Instant.now());
        var view = new DeviceController.ReportingRelationshipView(pressureReading, null);

        JsonNode json = mapper.readTree(mapper.writeValueAsString(view));

        assertTrue(json.get("reportsTo").isNull());
    }
}
