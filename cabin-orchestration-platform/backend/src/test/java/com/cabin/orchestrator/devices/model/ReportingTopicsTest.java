package com.cabin.orchestrator.devices.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportingTopicsTest {

    @Test
    void environmentalMeasurementTypesFeedComfortAir() {
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("temperature").orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("humidity").orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("co").orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("co2").orElseThrow());
        assertEquals(ReportingTopics.COMFORT_AIR, ReportingTopics.topicFor("air_quality_index").orElseThrow());
    }

    // D15's four energy measurement types all feed the Energy topic.
    @Test
    void d15EnergyMeasurementTypesFeedEnergy() {
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("power").orElseThrow());
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("energy").orElseThrow());
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("current").orElseThrow());
        assertEquals(ReportingTopics.ENERGY, ReportingTopics.topicFor("voltage").orElseThrow());
    }

    // pressure/battery are real D7MeasurementTypes values with no Topic
    // assignment in D16's current 5-topic scheme -- absent, not guessed.
    @Test
    void aMeasurementTypeWithNoTopicAssignmentIsAbsentNotGuessed() {
        assertTrue(ReportingTopics.topicFor("pressure").isEmpty());
        assertTrue(ReportingTopics.topicFor("battery").isEmpty());
        assertTrue(ReportingTopics.topicFor("unknown_field").isEmpty());
    }

    @Test
    void allFiveTopicsAreTheClosedD16Vocabulary() {
        assertEquals(5, ReportingTopics.ALL.size());
        assertTrue(ReportingTopics.ALL.containsAll(java.util.Set.of(
            "comfort_air", "security_presence", "energy", "alert_history", "occupancy")));
    }
}
