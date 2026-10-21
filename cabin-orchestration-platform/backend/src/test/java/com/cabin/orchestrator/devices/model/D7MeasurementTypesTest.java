package com.cabin.orchestrator.devices.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class D7MeasurementTypesTest {

    private static Path upwards(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.exists(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return null;
    }

    // DL-2026-10-21-01: the schema file had lagged the code by six values (D15's energy, current and
    // voltage, D16's occupancy, contact and water_leak). The schema is the single source; this fails if
    // either list gains or loses a value without the other.
    @Test
    void theSchemaEnumAndTheCodeVocabularyAreTheSameSet() throws IOException {
        Path schema = upwards("docs/ontology/schema/device-reporting-relationship.schema.json");
        assumeTrue(schema != null, "docs directory not present next to the backend");
        JsonNode enumNode = new ObjectMapper().readTree(schema.toFile())
            .path("properties").path("measurement_type").path("enum");
        Set<String> fromSchema = new HashSet<>();
        enumNode.forEach(n -> fromSchema.add(n.asText()));
        assertFalse(fromSchema.isEmpty(), "the schema's measurement_type enum");
        assertEquals(fromSchema, new HashSet<>(D7MeasurementTypes.all()),
            "device-reporting-relationship.schema.json and D7MeasurementTypes drifted apart");
    }


    @Test
    void exactSnakeCaseMatchesPassThrough() {
        assertEquals("temperature", D7MeasurementTypes.toMeasurementType("temperature").orElseThrow());
        assertEquals("co2", D7MeasurementTypes.toMeasurementType("co2").orElseThrow());
    }

    @Test
    void camelCaseIsConvertedToTheSnakeCaseEnumValue() {
        assertEquals("air_quality_index", D7MeasurementTypes.toMeasurementType("airQualityIndex").orElseThrow());
    }

    @Test
    void anUnrecognizedFieldIsAbsentNotGuessed() {
        assertTrue(D7MeasurementTypes.toMeasurementType("linkquality").isEmpty());
        assertTrue(D7MeasurementTypes.toMeasurementType("deviceId").isEmpty());
    }

    // D15 (Energy Device Ontology, 2026-09-05): voltage/current used to be
    // deliberately excluded (see Zigbee2MqttAdapter's own doc comment,
    // pre-D15) -- this locks in the reversal for power-monitoring devices.
    @Test
    void d15EnergyFieldsAreRecognized() {
        assertEquals("energy", D7MeasurementTypes.toMeasurementType("energy").orElseThrow());
        assertEquals("current", D7MeasurementTypes.toMeasurementType("current").orElseThrow());
        assertEquals("voltage", D7MeasurementTypes.toMeasurementType("voltage").orElseThrow());
    }

    // D16, Cowork ratification 2026-09-05: the real Z2M expose names for
    // security_presence's three signals -- confirmed live against
    // z2m-motion_entry/z2m-door_front_contact/z2m-leak_mech_room.
    @Test
    void d16SecurityPresenceFieldsAreRecognized() {
        assertEquals("occupancy", D7MeasurementTypes.toMeasurementType("occupancy").orElseThrow());
        assertEquals("contact", D7MeasurementTypes.toMeasurementType("contact").orElseThrow());
        assertEquals("water_leak", D7MeasurementTypes.toMeasurementType("water_leak").orElseThrow());
    }
}
