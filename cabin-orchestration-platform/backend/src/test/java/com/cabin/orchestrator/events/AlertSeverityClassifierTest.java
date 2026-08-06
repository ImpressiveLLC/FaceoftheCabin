package com.cabin.orchestrator.events;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AlertSeverityClassifierTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("cases")
    void classifiesAccordingToRules(Map<String, Object> attrs, String expected) {
        assertThat(AlertSeverityClassifier.classify(attrs)).isEqualTo(expected);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
            Arguments.of(Map.of("water_leak", true), "CRITICAL"),
            Arguments.of(Map.of("smoke", true), "CRITICAL"),
            Arguments.of(Map.of("alarm", true), "CRITICAL"),
            Arguments.of(Map.of("tamper", true), "WARN"),
            Arguments.of(Map.of("battery_low", true), "WARN"),
            Arguments.of(Map.of("contact", false), "WARN"),
            Arguments.of(Map.of("contact", true), "INFO"),
            Arguments.of(Map.of("occupancy", true, "linkquality", 150), "INFO"),
            Arguments.of(Map.of(), "INFO"),
            // CRITICAL must win even if a WARN-triggering attribute is also present
            Arguments.of(Map.of("water_leak", true, "battery_low", true), "CRITICAL"),
            // Non-boolean values for these keys must not accidentally match
            Arguments.of(Map.of("water_leak", "false"), "INFO"),
            Arguments.of(Map.of("water_leak", 1), "INFO")
        );
    }

    @Test
    void doesNotThrowOnNullAttributeValues() {
        Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("water_leak", null);
        assertThat(AlertSeverityClassifier.classify(attrs)).isEqualTo("INFO");
    }
}
