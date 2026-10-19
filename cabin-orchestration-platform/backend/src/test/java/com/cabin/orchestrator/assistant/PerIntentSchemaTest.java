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
        for (JsonNode intent : schema.path("oneOf").get(0).path("properties").path("intent").path("enum")) {
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

    private static ObjectNode envelope(String intent) {
        ObjectNode value = JSON.createObjectNode();
        value.put("intent", intent);
        value.putObject("slots");
        value.put("requires_confirmation", true);
        value.putNull("clarification");
        value.putNull("automation");
        return value;
    }
}
