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
 * Cowork-ratified 2026-09-05 (see the ontology artifact's Discrepancy Log):
 * COMFORT_AIR, SECURITY_PRESENCE, and ENERGY are the only Topics a service
 * entity can be assigned to -- ALERT_HISTORY and OCCUPANCY are computed
 * views over cross-cutting data (the alert log; an aggregate inference over
 * security_presence-tagged services), not a property any single service
 * has, so they're kept in the ALL vocabulary (the Topic picker needs all
 * five labels) but never returned by topicFor().
 *
 * NAMING TRAP: the Topic OCCUPANCY ("How busy is it?", an aggregate view)
 * is a completely different thing from the measurement_type "occupancy"
 * (Z2M's real field name for a motion sensor's PIR reading, which feeds
 * SECURITY_PRESENCE below). Sharing the English word is a coincidence of
 * two independent vocabularies, not a relationship -- do not "fix" this by
 * routing measurement_type "occupancy" to the Topic OCCUPANCY constant.
 *
 * voltage/current require device-type context, unlike every other entry:
 * both are dual-use, live confirmed 2026-09-05 -- a power-monitoring smart
 * plug's voltage/current are real energy-usage readings, but a
 * battery-powered contact/motion/leak sensor also incidentally reports its
 * own battery voltage/current numerically, and D7MeasurementTypes has no
 * device-type awareness at the capture layer. topicFor() takes the
 * reporting device's DeviceType specifically to resolve this -- see its
 * own doc. Every other measurement_type's Topic is unambiguous regardless
 * of device type.
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
    private static final Map<String, String> UNAMBIGUOUS_BY_MEASUREMENT_TYPE = Map.ofEntries(
        Map.entry("temperature", COMFORT_AIR),
        Map.entry("humidity", COMFORT_AIR),
        Map.entry("co", COMFORT_AIR),
        Map.entry("co2", COMFORT_AIR),
        Map.entry("air_quality_index", COMFORT_AIR),
        Map.entry("power", ENERGY),
        Map.entry("energy", ENERGY),
        // "occupancy" here is Z2M's real field name for a PIR motion
        // reading -- see this class's own NAMING TRAP note. Not the Topic
        // OCCUPANCY.
        Map.entry("occupancy", SECURITY_PRESENCE),
        Map.entry("contact", SECURITY_PRESENCE),
        Map.entry("water_leak", SECURITY_PRESENCE)
    );

    // Cowork ratification 2026-09-05: energy-topic assignment for these two
    // is conditioned on the reporting device actually being a power meter,
    // resolved at read time (not baked in at row-creation time) so a later
    // correction to a device's type inference is reflected automatically
    // rather than leaving a stale decision behind.
    private static final Set<String> DEVICE_TYPE_GATED_TO_POWER_METER = Set.of("voltage", "current");

    private ReportingTopics() {}

    /**
     * Which Topic a Service Entity's measurement_type feeds, given the
     * DeviceType of the device that reported it -- empty for a real
     * D7MeasurementTypes value with no Topic assignment (pressure, battery),
     * not an error. voltage/current only resolve to ENERGY when deviceType
     * is POWER_METER; from any other device type they're a real, valid
     * D7MeasurementTypes reading (a battery's own voltage) that simply
     * doesn't feed any Topic yet.
     */
    public static Optional<String> topicFor(String measurementType, DeviceType deviceType) {
        if (DEVICE_TYPE_GATED_TO_POWER_METER.contains(measurementType)) {
            return deviceType == DeviceType.POWER_METER ? Optional.of(ENERGY) : Optional.empty();
        }
        return Optional.ofNullable(UNAMBIGUOUS_BY_MEASUREMENT_TYPE.get(measurementType));
    }
}
