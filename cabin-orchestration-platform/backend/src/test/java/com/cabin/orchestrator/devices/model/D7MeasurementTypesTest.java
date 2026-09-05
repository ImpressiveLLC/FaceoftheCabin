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
