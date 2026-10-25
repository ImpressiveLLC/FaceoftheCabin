package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PerIntentSchemaTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final IntentSlotContract contract = new IntentSlotContract();

    @Test
    void schemaRejectsConceptForEveryOtherIntentBeforeSemanticChecks() {
        JsonNode schema = contract.schema();
        JsonNode concepts = schema.path("oneOf").get(1).path("properties")
            .path("slots").path("properties").path("concept").path("enum");
        java.util.List<JsonNode> others = new java.util.ArrayList<>();
        schema.path("oneOf").get(0).path("properties").path("intent").path("enum").forEach(others::add);
        schema.path("oneOf").get(2).path("properties").path("intent").path("enum").forEach(others::add);
        others.add(schema.path("oneOf").get(3).path("properties").path("intent").path("const"));
        for (JsonNode intent : others) {
            ObjectNode value = envelope(intent.asText());
            assertTrue(JsonSchemaLite.validate(value, schema).isEmpty(), intent.asText());
            for (JsonNode concept : concepts) {
                ((ObjectNode) value.get("slots")).put("concept", concept.asText());
                assertFalse(JsonSchemaLite.validate(value, schema).isEmpty(), intent + ": " + concept);
                assertThrows(IntentSlotContract.ContractViolation.class, () -> contract.validate(value.toString()));
            }
        }
    }

    @Test
    void oneOfRequiresExactlyOneMatchAndStillChecksSiblingConstraints() throws Exception {
        JsonNode schema = JSON.readTree("""
            {"oneOf":[{"type":"string"},{"type":"integer"}],"enum":["allowed",1]}
            """);
        assertTrue(JsonSchemaLite.validate(JSON.readTree("\"allowed\""), schema).isEmpty());
        assertFalse(JsonSchemaLite.validate(JSON.readTree("\"other\""), schema).isEmpty());
        assertFalse(JsonSchemaLite.validate(JSON.readTree("null"), schema).isEmpty());
        JsonNode overlap = JSON.readTree("{\"oneOf\":[{\"type\":\"string\"},{\"type\":\"string\"}]}");
        assertFalse(JsonSchemaLite.validate(JSON.readTree("\"allowed\""), overlap).isEmpty());
    }

    @Test
    void clarifyCannotAskForConfirmationButOtherIntentsAreUnchanged() {
        JsonNode schema = contract.schema();
        ObjectNode clarify = envelope("clarify");
        clarify.put("clarification", "Which device?");
        assertTrue(JsonSchemaLite.validate(clarify, schema).isEmpty());
        clarify.put("requires_confirmation", true);
        assertFalse(JsonSchemaLite.validate(clarify, schema).isEmpty());
        ObjectNode answer = envelope("answer_question");
        answer.put("requires_confirmation", false);
        assertTrue(JsonSchemaLite.validate(answer, schema).isEmpty());
    }

    @Test
    void intentsWhoseGoldHasNoSlotsCannotCarryAny() {
        JsonNode schema = contract.schema();
        for (String intent : new String[] {"answer_question", "clarify", "propose_automation"}) {
            assertTrue(JsonSchemaLite.validate(envelope(intent), schema).isEmpty(), intent);
            for (String key : new String[] {"device_name", "location", "unit"}) {
                ObjectNode bad = envelope(intent);
                ((ObjectNode) bad.get("slots")).put(key, "x");
                assertFalse(JsonSchemaLite.validate(bad, schema).isEmpty(), intent + "/" + key);
            }
        }
        for (String intent : new String[] {"set_device_state", "get_device_state", "set_device_attribute", "unsupported"}) {
            ObjectNode ok = envelope(intent);
            ((ObjectNode) ok.get("slots")).put("device_name", "lights");
            assertTrue(JsonSchemaLite.validate(ok, schema).isEmpty(), intent);
        }
    }

    @Test
    void unitIsAClosedListAndAnOffsetUntilCannotBeGenerated() throws Exception {
        JsonNode schema = contract.schema();
        for (String unit : new String[] {"C", "F", "K", "percent", "lux", "seconds", "minutes", "hours"}) {
            ObjectNode ok = envelope("set_device_attribute");
            ((ObjectNode) ok.get("slots")).put("unit", unit);
            assertTrue(JsonSchemaLite.validate(ok, schema).isEmpty(), unit);
        }
        // Observed in r4 (gold-08, gold-40): spelled-out and decorated units.
        for (String unit : new String[] {"Fahrenheit", "°F", "none", "None", "unknown", "null"}) {
            ObjectNode bad = envelope("set_device_attribute");
            ((ObjectNode) bad.get("slots")).put("unit", unit);
            assertFalse(JsonSchemaLite.validate(bad, schema).isEmpty(), unit);
        }
        assertTrue(JsonSchemaLite.validate(recurring("FREQ=DAILY;UNTIL=20271231T235959Z"), schema).isEmpty());
        assertTrue(JsonSchemaLite.validate(recurring("FREQ=WEEKLY;BYDAY=MO,-1FR;INTERVAL=2"), schema).isEmpty());
        // Observed in r4 (gold-32, gold-47): a datetime-with-offset UNTIL.
        assertFalse(JsonSchemaLite.validate(recurring("FREQ=DAILY;UNTIL=2026-12-31T23:59:59-06:00"), schema).isEmpty());
        assertFalse(JsonSchemaLite.validate(recurring("FREQ=DAILY;UNTIL=2026-12-31T23H59M-06"), schema).isEmpty());
        assertFalse(JsonSchemaLite.validate(recurring("FREQ=DAILY;FOO=1"), schema).isEmpty());
        assertFalse(JsonSchemaLite.validate(recurring("DAILY"), schema).isEmpty());
    }

    private static ObjectNode recurring(String rrule) {
        ObjectNode value = envelope("propose_automation");
        ObjectNode automation = value.putObject("automation");
        ObjectNode trigger = automation.putObject("trigger");
        trigger.put("kind", "time_recurring");
        trigger.put("start_time", "2027-01-01T07:00:00Z");
        trigger.put("rrule", rrule);
        automation.putArray("conditions");
        ObjectNode action = automation.putArray("actions").addObject();
        ObjectNode target = action.putObject("target");
        target.put("device_name", "heater");
        target.put("location", "mech room");
        target.put("site", "cabin");
        action.put("operation", "on");
        action.putNull("attribute");
        action.putNull("value");
        action.putNull("unit");
        action.put("delay_seconds", 0);
        return value;
    }

    private static ObjectNode envelope(String intent) {
        ObjectNode value = JSON.createObjectNode();
        value.put("intent", intent);
        value.putObject("slots");
        value.put("requires_confirmation", !"clarify".equals(intent));
        value.putNull("clarification");
        value.putNull("automation");
        return value;
    }
}
