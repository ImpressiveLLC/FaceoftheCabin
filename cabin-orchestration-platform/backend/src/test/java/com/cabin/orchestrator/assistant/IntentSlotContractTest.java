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
        assertTrue(checked >= 47, "expected the 47 gold fixtures, found " + checked);
    }

    @Test
    void everyHumanNeedIsAcceptedAndBrightIsNotASeparateConcept() throws ContractViolation {
        for (String concept : List.of("cold", "hot", "stuffy", "dark", "light", "arriving", "leaving", "bedtime", "nap", "wake_up", "tv_time")) {
            ParsedIntent parsed = contract.validate(need(concept));
            assertEquals("express_need", parsed.intent());
            assertEquals(concept, parsed.slots().get("concept"));
            assertTrue(parsed.requiresConfirmation());
        }
        // bright is an alias of light (concepts.json), so the model may not return it as a concept
        violation(contract, need("bright"));
        violation(contract, need("nap_time"));
    }

    private static String need(String concept) {
        return "{\"intent\":\"express_need\",\"slots\":{\"concept\":\"" + concept
            + "\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}";
    }

    @Test
    void aNeedRequiresAConceptConfirmationAndNothingElseMayCarryAConcept() {
        violation(contract, "{\"intent\":\"express_need\",\"slots\":{},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");
        assertTrue(violation(contract, need("light").replace("\"requires_confirmation\":true", "\"requires_confirmation\":false"))
            .getMessage().contains("confirmation"));
        violation(contract, "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"lights\",\"state\":\"on\",\"concept\":\"dark\"},"
            + "\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}");
        violation(contract, "{\"intent\":\"answer_question\",\"slots\":{\"concept\":\"cold\"},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}");
    }

    @Test
    void aTemperatureSettingNeedsAUnitCOrF() throws ContractViolation {
        String set = "{\"intent\":\"set_device_attribute\",\"slots\":{\"device_name\":\"thermostat\",\"attribute_type\":\"temperature\","
            + "\"attribute_value\":\"19\"%s},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}";
        violation(contract, set.formatted(""));
        violation(contract, set.formatted(",\"unit\":\"K\""));
        assertEquals("F", contract.validate(set.formatted(",\"unit\":\"F\"")).slots().get("unit"));
    }

    private static String automation(String trigger) {
        return "{\"intent\":\"propose_automation\",\"slots\":{},\"requires_confirmation\":true,\"clarification\":null,\"automation\":{\"trigger\":"
            + trigger + ",\"conditions\":[],\"actions\":[{\"target\":{\"device_name\":\"heater\",\"location\":\"mech room\",\"site\":\"cabin\"},"
            + "\"operation\":\"on\",\"attribute\":null,\"value\":null,\"unit\":null,\"delay_seconds\":0}]}}";
    }

    @Test
    void theNewTriggerKindsAreAcceptedAndUnknownOnesAreNot() {
        assertDoesNotThrow(() -> contract.validate(automation("{\"kind\":\"time_once\",\"at\":\"2027-01-01T07:00:00-06:00\"}")));
        assertDoesNotThrow(() -> contract.validate(automation("{\"kind\":\"time_recurring\",\"start_time\":\"2027-01-01T07:00:00Z\",\"rrule\":\"FREQ=DAILY;COUNT=5\"}")));
        assertDoesNotThrow(() -> contract.validate(automation("{\"kind\":\"state_changes_to\",\"target\":{\"device_name\":\"presence\",\"location\":\"mech room\",\"site\":\"cabin\"},\"attribute\":\"state\",\"value\":\"present\",\"for_seconds\":60}")));
        violation(contract, automation("{\"kind\":\"cron\",\"expr\":\"* * * * *\"}"));
        violation(contract, automation("{\"kind\":\"time_once\",\"at\":\"2027-01-01T07:00:00-06:00\",\"extra\":1}"));
        violation(contract, automation("{\"kind\":\"state_changes_to\",\"target\":{\"device_name\":\"presence\",\"location\":\"mech room\",\"site\":\"cabin\"},\"attribute\":\"state\",\"value\":\"present\",\"for_seconds\":-1}"));
    }

    @Test
    void aScheduleNeedsAnExplicitOffsetAndAWellFormedRecurrenceRule() {
        violation(contract, automation("{\"kind\":\"time_once\",\"at\":\"2027-01-01T07:00:00\"}"));
        violation(contract, automation("{\"kind\":\"time_once\",\"at\":\"tomorrow at 7\"}"));
        violation(contract, automation("{\"kind\":\"time_recurring\",\"start_time\":\"2027-01-01T07:00:00\",\"rrule\":\"FREQ=DAILY\"}"));
        for (String rule : List.of("DAILY", "FREQ=DAILY;FREQ=WEEKLY", "FREQ=DAILY;COUNT=3;UNTIL=20270101T000000Z", "FREQ=DAILY;COUNT=0",
                "FREQ=DAILY;INTERVAL=-1", "FREQ=DAILY;COUNT=x", "FREQ=DAILY;BYDAY", "FREQ=DAILY\\nDTSTART:20270101")) {
            violation(contract, automation("{\"kind\":\"time_recurring\",\"start_time\":\"2027-01-01T07:00:00Z\",\"rrule\":\"" + rule + "\"}"));
        }
    }

    @Test
    void theRequestEnvelopeMatchesContractPyAndEscapesTheText() {
        assertEquals("{\"text\": \"turn on the hall lights\"}", contract.requestContent("turn on the hall lights"));
        assertEquals("{\"text\": \"say \\\"hi\\\"\\nand café\"}", contract.requestContent("say \"hi\"\nand café"));
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
