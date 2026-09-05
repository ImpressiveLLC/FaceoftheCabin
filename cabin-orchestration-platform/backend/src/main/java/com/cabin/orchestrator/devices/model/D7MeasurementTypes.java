package com.cabin.orchestrator.devices.model;

import java.util.Optional;
import java.util.Set;

/**
 * The closed measurement_type vocabulary docs/ontology/schema/
 * device-reporting-relationship.schema.json declares. Deliberately closed,
 * not open -- extend only when a real new measurement type is onboarded,
 * not speculatively (matching that schema's own instruction). Shared by
 * every D7 writer (CabinEventService's empirical_observation path,
 * Zigbee2MqttAdapter's vendor_spec path) so they can never independently
 * drift on what counts as a real measurement type.
 */
public final class D7MeasurementTypes {

    private static final Set<String> TYPES = Set.of(
        "temperature", "humidity", "co2", "air_quality_index", "co", "pressure", "power", "battery",
        // D15 (Energy Device Ontology, 2026-09-05): added for the two real
        // Third Reality smart plugs (z2m-heater_mech_room,
        // z2m-smart_switch_breaker_box) -- both already report these live.
        "energy", "current", "voltage");

    private D7MeasurementTypes() {}

    /**
     * A CabinEvent payload key (e.g. "temperature", or camelCase like
     * "airQualityIndex") mapped to its measurement_type enum value, or
     * empty if this field isn't one of the recognized D7 measurement
     * types -- most payload keys aren't (deviceId, linkquality, etc.), and
     * that's the normal, expected case, not an error.
     */
    public static Optional<String> toMeasurementType(String semanticField) {
        if (semanticField == null) return Optional.empty();
        if (TYPES.contains(semanticField)) return Optional.of(semanticField);
        String snakeCase = camelToSnake(semanticField);
        return TYPES.contains(snakeCase) ? Optional.of(snakeCase) : Optional.empty();
    }

    private static String camelToSnake(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('_').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
