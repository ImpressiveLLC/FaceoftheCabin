package com.cabin.orchestrator.devices.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportingTopicsTest {

    @Test
    void environmentalMeasurementTypesFeedComfortAirRegardlessOfDeviceType() {
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("temperature", DeviceType.TEMPERATURE_SENSOR).orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("humidity", DeviceType.HUMIDITY_SENSOR).orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("co", DeviceType.CO_SENSOR).orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("co2", DeviceType.CO2_SENSOR).orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("air_quality_index", DeviceType.AIR_QUALITY_SENSOR).orElseThrow());
    }

    // D16, Cowork ratification 2026-09-05: motion/contact/leak's real Z2M
    // field names (occupancy/contact/water_leak), confirmed live, feed
    // security_presence regardless of device type -- unlike voltage/current,
    // there's no dual-use ambiguity here.
    @Test
    void presenceSignalMeasurementTypesFeedSecurityPresence() {
        assertEquals(ReportingTopics.SECURITY_PRESENCE, ReportingTopics.topicFor("occupancy", DeviceType.MOTION_SENSOR).orElseThrow());
        assertEquals(ReportingTopics.SECURITY_PRESENCE, ReportingTopics.topicFor("contact", DeviceType.CONTACT_SENSOR).orElseThrow());
        assertEquals(ReportingTopics.SECURITY_PRESENCE, ReportingTopics.topicFor("water_leak", DeviceType.WATER_LEAK_SENSOR).orElseThrow());
    }

    // The Topic OCCUPANCY and the measurement_type "occupancy" share an
    // English word by coincidence -- ReportingTopics' own NAMING TRAP doc
    // comment explains why. This locks in that a motion sensor's occupancy
    // reading never resolves to the OCCUPANCY topic constant.
    @Test
    void occupancyMeasurementTypeNeverResolvesToTheOccupancyTopic() {
        String resolved = ReportingTopics.topicFor("occupancy", DeviceType.MOTION_SENSOR).orElseThrow();
        assertEquals(ReportingTopics.SECURITY_PRESENCE, resolved);
        assertNotEquals(ReportingTopics.OCCUPANCY, resolved);
    }

    // D15's four energy measurement types all feed the Energy topic when
    // power/energy are reporting (unambiguous -- only a power meter reports
    // these two at all).
    @Test
    void powerAndEnergyAlwaysFeedEnergyTopic() {
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("power", DeviceType.POWER_METER).orElseThrow());
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("energy", DeviceType.POWER_METER).orElseThrow());
    }

    // Cowork ratification 2026-09-05: voltage/current are dual-use --
    // confirmed live a door contact sensor's own battery voltage was
    // wrongly tagged "energy" before this fix. Only a real POWER_METER's
    // voltage/current is an energy-topic reading.
    @Test
    void voltageAndCurrentOnlyFeedEnergyForARealPowerMeter() {
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("voltage", DeviceType.POWER_METER).orElseThrow());
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("current", DeviceType.POWER_METER).orElseThrow());
    }

    @Test
    void voltageAndCurrentFromANonPowerMeterDeviceFeedNoTopic() {
        // z2m-door_front_contact's real, live battery voltage reading --
        // the exact bug this fix closes.
        assertTrue(ReportingTopics.topicFor("voltage", DeviceType.CONTACT_SENSOR).isEmpty());
        assertTrue(ReportingTopics.topicFor("current", DeviceType.MOTION_SENSOR).isEmpty());
    }

    // pressure/battery are real D7MeasurementTypes values with no Topic
    // assignment in D16's current 5-topic scheme -- absent, not guessed.
    @Test
    void aMeasurementTypeWithNoTopicAssignmentIsAbsentNotGuessed() {
        assertTrue(ReportingTopics.topicFor("pressure", DeviceType.TEMPERATURE_SENSOR).isEmpty());
        assertTrue(ReportingTopics.topicFor("battery", DeviceType.MOTION_SENSOR).isEmpty());
        assertTrue(ReportingTopics.topicFor("unknown_field", DeviceType.MOTION_SENSOR).isEmpty());
    }

    @Test
    void allFiveTopicsAreTheClosedD16Vocabulary() {
        assertEquals(5, ReportingTopics.ALL.size());
        assertTrue(ReportingTopics.ALL.containsAll(java.util.Set.of(
            "comfort_air", "security_presence", "energy", "alert_history", "occupancy")));
    }
}
