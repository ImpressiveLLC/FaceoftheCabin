package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
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
 * express (mutations and expressed needs require confirmation, a clarification carries a question, only an
 * automation proposal carries a plan, a need carries a concept and nothing else does, a temperature setting
 * carries C or F, a schedule carries an explicit UTC offset, a recurrence rule is well formed). Anything else is a
 * violation and the caller fails closed.
 *
 * <p>Not ported, on purpose: the trusted {@code card_target} envelope (the propose route has no selected card),
 * and parsing an RRULE against its start time (Python does this with dateutil; here the rule's shape is checked
 * and the workflow engine must still refuse an unparseable rule before it saves one).
 */
@Component
public class IntentSlotContract {

    static final Set<String> MUTATIONS = Set.of("set_device_state", "set_device_attribute", "propose_automation", "express_need");

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
        JsonNode slotNode = value.get("slots");
        if ("express_need".equals(intent) && !slotNode.has("concept")) violations.add("a need requires a concept");
        if (!"express_need".equals(intent) && slotNode.has("concept")) violations.add("a concept belongs only to a need request");
        if ("set_device_attribute".equals(intent) && "temperature".equals(slotNode.path("attribute_type").asText())
                && !Set.of("C", "F").contains(slotNode.path("unit").asText())) {
            violations.add("a temperature requires an explicit unit, C or F; otherwise clarify");
        }
        if (!automation.isNull()) checkTrigger(automation.path("trigger"), violations);
        if (!violations.isEmpty()) throw new ContractViolation(violations);

        Map<String, String> slots = new LinkedHashMap<>();
        value.get("slots").fields().forEachRemaining(e -> slots.put(e.getKey(), e.getValue().asText()));
        return new ParsedIntent(intent, Map.copyOf(slots), requiresConfirmation,
            clarification.isNull() ? null : clarification.asText(), automation.isNull() ? null : automation);
    }

    private static void checkTrigger(JsonNode trigger, List<String> violations) {
        String kind = trigger.path("kind").asText("");
        if (!"time_once".equals(kind) && !"time_recurring".equals(kind)) return;
        String stamp = trigger.path("time_once".equals(kind) ? "at" : "start_time").asText("");
        try {
            OffsetDateTime.parse(stamp);
        } catch (DateTimeParseException e) {
            violations.add("a schedule requires an ISO datetime with an explicit UTC offset");
            return;
        }
        if (!"time_recurring".equals(kind)) return;
        String rule = trigger.path("rrule").asText("");
        if (rule.indexOf('\n') >= 0 || rule.indexOf('\r') >= 0 || !rule.startsWith("FREQ=") || rule.length() > 500) {
            violations.add("expected one RFC 5545 RRULE value beginning FREQ=");
            return;
        }
        Map<String, String> parts = new LinkedHashMap<>();
        for (String part : rule.split(";")) {
            int eq = part.indexOf('=');
            if (eq < 1 || eq == part.length() - 1 || parts.put(part.substring(0, eq), part.substring(eq + 1)) != null) {
                violations.add("RRULE parts must be unique key=value pairs");
                return;
            }
        }
        if (parts.containsKey("COUNT") && parts.containsKey("UNTIL")) violations.add("an RRULE cannot combine COUNT and UNTIL");
        for (String key : List.of("COUNT", "INTERVAL")) {
            String number = parts.get(key);
            if (number != null && !number.matches("[1-9][0-9]*")) violations.add("RRULE " + key + " must be a positive integer");
        }
    }

    /**
     * The user turn the prompt describes ("Input is a JSON request with text"): {@code {"text": "..."}}, written
     * the way contract.py's request_content writes it so the model sees what training and evaluation showed it.
     */
    public String requestContent(String text) {
        try {
            return "{\"text\": " + json.writeValueAsString(text) + "}";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a String is always serialisable", e);
        }
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
