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
}
