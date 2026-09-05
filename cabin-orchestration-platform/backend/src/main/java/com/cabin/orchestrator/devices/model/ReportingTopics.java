package com.cabin.orchestrator.devices.model;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * D16 (Reporting Topics IA, Sprint 4): the five user-question-driven
 * reporting topics a Service Entity's reports_to[] value resolves to.
 * Deliberately closed, matching D7MeasurementTypes' own discipline -- the
 * whole point of this decision is a Topic picker as the ONLY reporting
 * navigation, not a 15+-measurement_type dropdown (see the ontology
 * decisions artifact's own "what NOT to build" note).
 *
 * Sprint 4 scope is narrower than the full Topic-picker IA: seed these
 * constants and wire the mapping that already has somewhere real to attach
 * to. COMFORT_AIR and ENERGY genuinely do -- every measurement_type they
 * cover already backs real device_reporting_relationship rows (D7/D13/D15).
 * SECURITY_PRESENCE does not, today: motion/contact/leak devices are
 * binary/event-driven, never produce a numeric vendorReportedFields entry,
 * and so never get a device_reporting_relationship row at all -- there is
 * no existing per-service place to attach that mapping to yet. ALERT_HISTORY
 * and OCCUPANCY are event-log-level aggregate views by D16's own
 * description ("all types -> alert event log", "aggregate motion + contact
 * patterns"), not a property any single service has -- they don't need a
 * topicFor() entry at all. Flagged rather than silently worked around by
 * inventing a service-entity row for device types that don't have one.
 */
public final class ReportingTopics {

    public static final String COMFORT_AIR = "comfort_air";
    public static final String SECURITY_PRESENCE = "security_presence";
    public static final String ENERGY = "energy";
    public static final String ALERT_HISTORY = "alert_history";
    public static final String OCCUPANCY = "occupancy";

    public static final Set<String> ALL = Set.of(COMFORT_AIR, SECURITY_PRESENCE, ENERGY, ALERT_HISTORY, OCCUPANCY);

    // co2 isn't named explicitly in D16's own "temperature, humidity, co,
    // air_quality" table -- read as shorthand covering every real
    // environmental reading this schema models, not a deliberate exclusion,
    // since there's no other Topic co2 could plausibly belong to.
    private static final Map<String, String> BY_MEASUREMENT_TYPE = Map.ofEntries(
        Map.entry("temperature", COMFORT_AIR),
        Map.entry("humidity", COMFORT_AIR),
        Map.entry("co", COMFORT_AIR),
        Map.entry("co2", COMFORT_AIR),
        Map.entry("air_quality_index", COMFORT_AIR),
        Map.entry("power", ENERGY),
        Map.entry("energy", ENERGY),
        Map.entry("current", ENERGY),
        Map.entry("voltage", ENERGY)
    );

    private ReportingTopics() {}

    /**
     * Which Topic a Service Entity's measurement_type feeds -- empty for a
     * real D7MeasurementTypes value with no Topic assignment yet
     * (pressure, battery), not an error.
     */
    public static Optional<String> topicFor(String measurementType) {
        return Optional.ofNullable(BY_MEASUREMENT_TYPE.get(measurementType));
    }
}
