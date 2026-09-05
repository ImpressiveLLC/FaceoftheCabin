package com.cabin.orchestrator.devices.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class D7MeasurementTypesTest {

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
}
