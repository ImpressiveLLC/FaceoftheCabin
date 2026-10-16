package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Java side of the intent/slot contract in docs/ai-assistant/training/intent-slot (HO-2026-10-16 step 3).
 * The schema and system prompt are copies of the files in that directory; IntentSlotContractTest fails if
 * either copy differs from the original, and runs every gold fixture through {@link #validate}.
 *
 * <p>{@link #validate} is {@code contract.py}'s {@code validate()}: the schema, plus the rules the schema cannot
 * express (mutations require confirmation, a clarification carries a question, only an automation proposal
 * carries a plan). Anything else is a violation and the caller fails closed.
 */
@Component
public class IntentSlotContract {

    static final Set<String> MUTATIONS = Set.of("set_device_state", "set_device_attribute", "propose_automation");

    /** Strict on purpose: trailing text after the object and duplicate keys are both ways to hide a second answer. */
    private final ObjectMapper json = new ObjectMapper()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final JsonNode schema;
    private final String systemPrompt;

    public IntentSlotContract() {
        this.schema = readSchema();
        this.systemPrompt = readResource("assistant/system.txt").strip();
    }

    /** What the model said, after validation. {@code slots} holds only the contract's string slots. */
    public record ParsedIntent(String intent, Map<String, String> slots, boolean requiresConfirmation,
                               String clarification, JsonNode automation) {}

    /** Raised when model output is not a valid proposal. The message lists every violation found. */
    public static class ContractViolation extends Exception {
        private final List<String> violations;

        ContractViolation(List<String> violations) {
            super(String.join("; ", violations));
            this.violations = List.copyOf(violations);
        }

        public List<String> violations() { return violations; }
    }

    public String systemPrompt() { return systemPrompt; }

    /** The JSON Schema handed to Ollama as the structured-output {@code format}. */
    public JsonNode schema() { return schema; }

    public ParsedIntent validate(String modelOutput) throws ContractViolation {
        JsonNode value;
        try {
            value = json.readTree(modelOutput == null ? "" : modelOutput);
        } catch (JsonProcessingException e) {
            throw new ContractViolation(List.of("model output is not JSON"));
        }
        if (value == null || value.isMissingNode()) {
            throw new ContractViolation(List.of("model output is empty"));
        }
        List<String> violations = new ArrayList<>(JsonSchemaLite.validate(value, schema));
        if (!violations.isEmpty()) throw new ContractViolation(violations);

        String intent = value.get("intent").asText();
        boolean requiresConfirmation = value.get("requires_confirmation").asBoolean();
        JsonNode clarification = value.get("clarification");
        JsonNode automation = value.get("automation");
        if (MUTATIONS.contains(intent) && !requiresConfirmation) violations.add("mutations require confirmation");
        if ("clarify".equals(intent) && (clarification.isNull() || clarification.asText().isBlank())) {
            violations.add("a clarification needs a question");
        }
        if ("propose_automation".equals(intent)) {
            if (automation.isNull()) violations.add("an automation proposal needs a structured plan");
        } else if (!automation.isNull()) {
            violations.add("only automation proposals carry a plan");
        }
        if (!violations.isEmpty()) throw new ContractViolation(violations);

        Map<String, String> slots = new LinkedHashMap<>();
        value.get("slots").fields().forEachRemaining(e -> slots.put(e.getKey(), e.getValue().asText()));
        return new ParsedIntent(intent, Map.copyOf(slots), requiresConfirmation,
            clarification.isNull() ? null : clarification.asText(), automation.isNull() ? null : automation);
    }

    private JsonNode readSchema() {
        try {
            return json.readTree(readResource("assistant/intent-slot.schema.json"));
        } catch (IOException e) {
            throw new IllegalStateException("assistant/intent-slot.schema.json is not valid JSON", e);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = IntentSlotContract.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " is missing from the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + path, e);
        }
    }
}
