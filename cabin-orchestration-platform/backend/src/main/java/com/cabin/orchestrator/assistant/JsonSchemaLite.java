package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The small subset of JSON Schema that docs/ai-assistant/training/intent-slot/intent-slot.schema.json uses, so
 * the backend validates model output against the same file the offline tooling does and the two cannot drift
 * (no schema library is on the classpath). Supported: type (one or several), enum, properties, required,
 * additionalProperties:false, minLength, minimum, maximum, minItems, maxItems, items, anyOf. Anything else in a
 * schema is ignored, so this is for that contract only, not a general validator.
 */
final class JsonSchemaLite {

    private JsonSchemaLite() {}

    /** Empty list means the value satisfies the schema. */
    static List<String> validate(JsonNode value, JsonNode schema) {
        List<String> errors = new ArrayList<>();
        check(value, schema, "$", errors);
        return errors;
    }

    private static void check(JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (schema.has("anyOf")) {
            for (JsonNode option : schema.get("anyOf")) {
                List<String> attempt = new ArrayList<>();
                check(value, option, path, attempt);
                if (attempt.isEmpty()) return;
            }
            errors.add(path + ": matches none of the allowed shapes");
            return;
        }
        if (schema.has("type") && !typeMatches(value, schema.get("type"))) {
            errors.add(path + ": expected " + schema.get("type") + " but found " + describe(value));
            return;
        }
        if (schema.has("enum")) {
            boolean found = false;
            for (JsonNode allowed : schema.get("enum")) {
                if (allowed.equals(value)) { found = true; break; }
            }
            if (!found) errors.add(path + ": " + value + " is not one of " + schema.get("enum"));
        }
        if (value.isTextual() && schema.has("minLength") && value.asText().length() < schema.get("minLength").asInt()) {
            errors.add(path + ": shorter than " + schema.get("minLength").asInt());
        }
        if (value.isIntegralNumber()) {
            if (schema.has("minimum") && value.asLong() < schema.get("minimum").asLong()) errors.add(path + ": below " + schema.get("minimum"));
            if (schema.has("maximum") && value.asLong() > schema.get("maximum").asLong()) errors.add(path + ": above " + schema.get("maximum"));
        }
        if (value.isArray()) {
            if (schema.has("minItems") && value.size() < schema.get("minItems").asInt()) errors.add(path + ": fewer than " + schema.get("minItems").asInt() + " items");
            if (schema.has("maxItems") && value.size() > schema.get("maxItems").asInt()) errors.add(path + ": more than " + schema.get("maxItems").asInt() + " items");
            if (schema.has("items")) {
                for (int i = 0; i < value.size(); i++) check(value.get(i), schema.get("items"), path + "[" + i + "]", errors);
            }
        }
        if (value.isObject()) {
            JsonNode properties = schema.path("properties");
            if (schema.has("required")) {
                for (JsonNode required : schema.get("required")) {
                    if (!value.has(required.asText())) errors.add(path + ": missing required field " + required.asText());
                }
            }
            Iterator<String> names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (properties.has(name)) {
                    check(value.get(name), properties.get(name), path + "." + name, errors);
                } else if (schema.path("additionalProperties").isBoolean() && !schema.get("additionalProperties").asBoolean()) {
                    errors.add(path + ": unexpected field " + name);
                }
            }
        }
    }

    private static boolean typeMatches(JsonNode value, JsonNode type) {
        if (type.isArray()) {
            for (JsonNode t : type) if (typeMatches(value, t)) return true;
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "boolean" -> value.isBoolean();
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "null" -> value.isNull();
            default -> false;
        };
    }

    private static String describe(JsonNode value) {
        return value.getNodeType().name().toLowerCase();
    }
}
