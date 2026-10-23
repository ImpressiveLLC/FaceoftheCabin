package com.cabin.orchestrator.integrations.homeassistant;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Field meaning comes from the source service, never the shared POWER_METER type. */
public final class ElectricalMeasurement {
    private static final Set<String> FIELDS = Set.of("power", "energy", "voltage", "current");
    private ElectricalMeasurement() {}

    public static String field(String entityId, Map<String, Object> attributes) {
        if (entityId == null || !entityId.startsWith("sensor.")) return null;
        if ("config".equals(attributes.get("entity_category"))) return null;
        String quantity = String.valueOf(attributes.get("device_class"));
        return FIELDS.contains(quantity) ? quantity : null;
    }

    public static boolean applies(String entityId, Map<String, Object> attributes) {
        return FIELDS.contains(String.valueOf(attributes.get("device_class")));
    }

    public static void normalize(String entityId, String state, Map<String, Object> attributes) {
        if (!applies(entityId, attributes)) return;
        FIELDS.forEach(attributes::remove);
        String field = field(entityId, attributes);
        attributes.put("sourceReportedFields", field == null ? List.of() : List.of(field));
        attributes.put("serviceRole", field == null ? "configuration" :
            "diagnostic".equals(attributes.get("entity_category")) ? "diagnostic" : "measurement");
        attributes.put("value", state);
        attributes.put("acquisitionTimeKnown", false); // HA cache reads are not acquisition evidence.
        if (field == null) return;
        Double factor = factor(field, String.valueOf(attributes.get("unit_of_measurement")));
        if (factor == null) {
            attributes.put("measurementStatus", "unsupported_unit");
            return;
        }
        try {
            double value = Double.parseDouble(state) * factor;
            if (!Double.isFinite(value)) throw new NumberFormatException();
            attributes.put(field, value);
            attributes.put("measurementStatus", "acquisition_time_unknown");
        } catch (NumberFormatException | NullPointerException invalid) {
            attributes.put("measurementStatus", "unavailable");
        }
    }

    private static Double factor(String field, String unit) {
        return switch (field + ":" + unit.trim()) {
            case "power:W", "energy:kWh", "voltage:V", "current:A" -> 1.0;
            case "power:kW" -> 1000.0;
            case "energy:Wh", "voltage:mV", "current:mA" -> 0.001;
            default -> null;
        };
    }
}