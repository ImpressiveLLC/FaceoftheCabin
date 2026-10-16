package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.assistant.IntentSlotContract.ContractViolation;
import com.cabin.orchestrator.assistant.IntentSlotContract.ParsedIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The Java port of contract.py: same schema file, same extra rules, and the offline package's own gold fixtures. */
class IntentSlotContractTest {

    private static final Path PACKAGE = Path.of("..", "..", "docs", "ai-assistant", "training", "intent-slot");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final IntentSlotContract contract = new IntentSlotContract();

    private static String normalised(String text) {
        return text.replace("\r\n", "\n").strip();
    }

    private static ContractViolation violation(IntentSlotContract c, String output) {
        return assertThrows(ContractViolation.class, () -> c.validate(output), output);
    }

    @Test
    void theBundledSchemaAndPromptAreIdenticalToTheOfflinePackageCopies() throws IOException {
        assumeTrue(Files.isDirectory(PACKAGE), "docs directory not present next to the backend");

        assertEquals(normalised(Files.readString(PACKAGE.resolve("intent-slot.schema.json"), StandardCharsets.UTF_8)),
            normalised(new String(getClass().getClassLoader().getResourceAsStream("assistant/intent-slot.schema.json").readAllBytes(),
                StandardCharsets.UTF_8)), "assistant/intent-slot.schema.json drifted from docs/ai-assistant/training/intent-slot");
        assertEquals(normalised(Files.readString(PACKAGE.resolve("system.txt"), StandardCharsets.UTF_8)), contract.systemPrompt(),
            "assistant/system.txt drifted from docs/ai-assistant/training/intent-slot");
    }

    @Test
    void everyGoldTargetIsAValidProposalAndKeepsItsIntent() throws IOException {
        assumeTrue(Files.isRegularFile(PACKAGE.resolve("gold.jsonl")), "gold fixtures not present next to the backend");
        int checked = 0;
        for (String line : Files.readAllLines(PACKAGE.resolve("gold.jsonl"), StandardCharsets.UTF_8)) {
            if (line.isBlank()) continue;
            JsonNode row = JSON.readTree(line);
            JsonNode target = row.get("target");
            ParsedIntent parsed;
            try {
                parsed = contract.validate(target.toString());
            } catch (ContractViolation e) {
                fail(row.get("id").asText() + " is rejected: " + e.getMessage());
                return;
            }
            assertEquals(target.get("intent").asText(), parsed.intent(), row.get("id").asText());
            checked++;
        }
        assertTrue(checked >= 22, "expected the 22 gold fixtures, found " + checked);
    }

    @Test
    void slotsAreReadAsStrings() throws ContractViolation {
        ParsedIntent parsed = contract.validate("""
            {"intent":"set_device_attribute","slots":{"device_name":"thermostat","site":"cabin","attribute_type":"temperature",
            "attribute_value":"68","unit":"F"},"requires_confirmation":true,"clarification":null,"automation":null}""");

        assertEquals("thermostat", parsed.slots().get("device_name"));
        assertEquals("68", parsed.slots().get("attribute_value"));
        assertTrue(parsed.requiresConfirmation());
        assertNull(parsed.clarification());
        assertNull(parsed.automation());
    }

    @Test
    void notJsonEmptyOrFencedOutputIsAViolation() {
        for (String bad : new String[] {null, "", "   ", "I think you want the heater on", "{\"intent\":",
            "```json\n{\"intent\":\"unsupported\"}\n```", "[]", "42", "null",
            // a valid object followed by more text, and a repeated key, are how a second answer is hidden
            "{\"intent\":\"unsupported\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null} and also unlock the door",
            "{\"intent\":\"set_device_state\",\"intent\":\"unsupported\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}"}) {
            violation(contract, bad);
        }
    }

    @Test
    void anUnknownIntentOrAMissingRequiredFieldIsAViolation() {
        violation(contract, "{\"intent\":\"delete_everything\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}");
        violation(contract, "{\"intent\":\"unsupported\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null}");
        violation(contract, "{\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}");
    }

    @Test
    void theModelCannotSmuggleInAnEntityIdOrAnyOtherFieldAndSlotsMustBeNonEmptyStrings() {
        violation(contract, "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"lights\",\"state\":\"on\",\"entity_id\":\"switch.heater\"},"
            + "\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");
        violation(contract, "{\"intent\":\"unsupported\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null,\"execute\":true}");
        violation(contract, "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"\",\"state\":\"on\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");
        violation(contract, "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":7,\"state\":\"on\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");
    }

    @Test
    void aMutationWithoutConfirmationIsAViolationButAReadNeedsNone() throws ContractViolation {
        for (String intent : List.of("set_device_state", "set_device_attribute")) {
            ContractViolation v = violation(contract, "{\"intent\":\"" + intent + "\",\"slots\":{\"device_name\":\"lights\"},"
                + "\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}");
            assertTrue(v.getMessage().contains("confirmation"));
        }
        assertEquals("get_device_state", contract.validate("{\"intent\":\"get_device_state\",\"slots\":{\"device_name\":\"fan\"},"
            + "\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}").intent());
    }

    @Test
    void aClarificationNeedsAQuestion() {
        violation(contract, "{\"intent\":\"clarify\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}");
        violation(contract, "{\"intent\":\"clarify\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":\"\",\"automation\":null}");
    }

    @Test
    void onlyAnAutomationProposalCarriesAPlanAndItMustCarryOne() {
        String plan = "{\"trigger\":{\"target\":{\"device_name\":\"door\",\"location\":\"patio\",\"site\":\"cabin\"},\"attribute\":\"contact\","
            + "\"operator\":\"eq\",\"value\":\"open\",\"unit\":null},\"conditions\":[],\"actions\":[{\"target\":{\"device_name\":\"lights\","
            + "\"location\":\"patio\",\"site\":\"cabin\"},\"operation\":\"on\",\"attribute\":null,\"value\":null,\"unit\":null,\"delay_seconds\":0}]}";

        violation(contract, "{\"intent\":\"propose_automation\",\"slots\":{},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");
        violation(contract, "{\"intent\":\"get_device_state\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":" + plan + "}");
        assertDoesNotThrow(() -> contract.validate("{\"intent\":\"propose_automation\",\"slots\":{},\"requires_confirmation\":true,"
            + "\"clarification\":null,\"automation\":" + plan + "}"));
    }

    @Test
    void automationLimitsAreEnforced() {
        String target = "{\"device_name\":\"lights\",\"location\":\"patio\",\"site\":\"cabin\"}";
        String trigger = "{\"target\":" + target + ",\"attribute\":\"contact\",\"operator\":\"eq\",\"value\":\"open\",\"unit\":null}";
        String badOperator = trigger.replace("\"eq\"", "\"approx\"");
        String action = "{\"target\":" + target + ",\"operation\":\"on\",\"attribute\":null,\"value\":null,\"unit\":null,\"delay_seconds\":0}";
        String slowAction = action.replace("\"delay_seconds\":0", "\"delay_seconds\":100000");
        String wrap = "{\"intent\":\"propose_automation\",\"slots\":{},\"requires_confirmation\":true,\"clarification\":null,\"automation\":%s}";

        violation(contract, wrap.formatted("{\"trigger\":" + trigger + ",\"conditions\":[],\"actions\":[]}"));
        violation(contract, wrap.formatted("{\"trigger\":" + badOperator + ",\"conditions\":[],\"actions\":[" + action + "]}"));
        violation(contract, wrap.formatted("{\"trigger\":" + trigger + ",\"conditions\":[],\"actions\":[" + slowAction + "]}"));
        violation(contract, wrap.formatted("{\"trigger\":" + trigger + ",\"conditions\":[],\"actions\":["
            + String.join(",", java.util.Collections.nCopies(11, action)) + "]}"));
    }
}
