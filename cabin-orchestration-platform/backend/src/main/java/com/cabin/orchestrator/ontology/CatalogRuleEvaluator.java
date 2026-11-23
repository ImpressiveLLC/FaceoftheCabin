package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Catalog S3b (design docs/governance/proposals/2026-11-23-s3b-rule-shadow-design.md): evaluates the catalog's
 * match-rule predicates ({@code when}) independently of the three {@code inferType} methods. Rules are tried in
 * catalog order per site and the first match wins; the last rule of each site is the {@code always} catch-all.
 *
 * <p>The grammar is closed and data only: {@code all}, {@code any}, {@code not}, {@code equals}, {@code contains},
 * {@code present}, {@code capability}, {@code exposes_property}, {@code exposes_relay}, {@code always}. Field values
 * are extracted per site exactly as that site's classifier extracts them (see {@link SiteInput}); the evaluator
 * reimplements those contracts and never calls the classifiers or their helpers, so a drift between the catalog and
 * the code shows up as a disagreement instead of being hidden.
 *
 * <p>{@link #load} validates the whole rule set; a malformed set throws {@link InvalidRules}, and callers in shadow
 * mode report evaluation as unavailable rather than failing discovery. Nothing here is wired into a runtime path yet.
 */
public final class CatalogRuleEvaluator {

    public static final List<String> SITES = List.of("home_assistant", "zigbee2mqtt", "mqtt_bridge");
    private static final Map<String, Set<String>> FIELDS = Map.of(
        "home_assistant", Set.of("domain", "device_class", "unit_of_measurement", "friendly_name"),
        "zigbee2mqtt", Set.of("model", "description", "vendor", "friendly_name"),
        "mqtt_bridge", Set.of());
    private static final Set<String> OPERATORS = Set.of("all", "any", "not", "equals", "contains", "present",
        "capability", "exposes_property", "exposes_relay", "always");
    private static final Set<String> NON_RELAY_EXPOSE_TYPES = Set.of("light", "lock", "cover", "fan", "climate");

    public static final class InvalidRules extends RuntimeException {
        InvalidRules(String message) { super(message); }
    }

    public record Rule(String id, String site, DeviceType returns, JsonNode when, boolean workaround) {}

    public record Match(String ruleId, DeviceType type, boolean workaround) {}

    private final Map<String, List<Rule>> bySite;

    private CatalogRuleEvaluator(Map<String, List<Rule>> bySite) {
        this.bySite = bySite;
    }

    /** Validates and loads the {@code match_rules} array of a generated catalog. */
    public static CatalogRuleEvaluator load(JsonNode matchRules) {
        if (matchRules == null || !matchRules.isArray()) throw new InvalidRules("match_rules is not an array");
        Map<String, List<Rule>> bySite = new LinkedHashMap<>();
        SITES.forEach(site -> bySite.put(site, new ArrayList<>()));
        Set<String> ids = new HashSet<>();
        for (JsonNode node : matchRules) {
            String id = node.path("id").asText("");
            String site = node.path("site").asText("");
            if (id.isEmpty() || !ids.add(id)) throw new InvalidRules("missing or duplicate rule id '" + id + "'");
            if (!bySite.containsKey(site)) throw new InvalidRules(id + ": unknown site '" + site + "'");
            JsonNode when = node.get("when");
            if (when == null || !when.isObject()) throw new InvalidRules(id + ": no `when` predicate");
            validate(id, site, when);
            DeviceType returns;
            try {
                returns = DeviceType.valueOf(node.path("returns").asText());
            } catch (IllegalArgumentException e) {
                throw new InvalidRules(id + ": returns an unknown type");
            }
            bySite.get(site).add(new Rule(id, site, returns, when, node.has("workaround")));
        }
        for (String site : SITES) {
            List<Rule> rules = bySite.get(site);
            if (rules.isEmpty() || !isAlways(rules.get(rules.size() - 1).when())) {
                throw new InvalidRules(site + ": the last rule must be the `always` catch-all");
            }
            for (int i = 0; i < rules.size() - 1; i++) {
                if (isAlways(rules.get(i).when())) throw new InvalidRules(rules.get(i).id() + ": catch-all is not last");
            }
            bySite.put(site, Collections.unmodifiableList(rules));
        }
        return new CatalogRuleEvaluator(Collections.unmodifiableMap(bySite));
    }

    public List<Rule> rules(String site) {
        return bySite.getOrDefault(site, List.of());
    }

    /** The first rule of the input's site whose predicate holds. Always returns a match (the catch-all). */
    public Match evaluate(SiteInput input) {
        for (Rule rule : bySite.get(input.site())) {
            if (holds(rule.when(), input)) return new Match(rule.id(), rule.returns(), rule.workaround());
        }
        throw new IllegalStateException("no catch-all for " + input.site());  // load() makes this unreachable
    }

    // ---------------------------------------------------------------------------------------- validation

    private static boolean isAlways(JsonNode when) {
        return when.size() == 1 && when.has("always") && when.get("always").isBoolean() && when.get("always").asBoolean();
    }

    private static void validate(String id, String site, JsonNode node) {
        if (!node.isObject() || node.size() != 1) throw new InvalidRules(id + ": each predicate has exactly one operator");
        String op = node.fieldNames().next();
        if (!OPERATORS.contains(op)) throw new InvalidRules(id + ": unknown operator '" + op + "'");
        JsonNode arg = node.get(op);
        switch (op) {
            case "all", "any" -> {
                if (!arg.isArray() || arg.isEmpty()) throw new InvalidRules(id + ": `" + op + "` needs a non-empty list");
                arg.forEach(child -> validate(id, site, child));
            }
            case "not" -> validate(id, site, arg);
            case "equals", "contains" -> {
                String field = arg.path("field").asText("");
                if (!FIELDS.get(site).contains(field)) throw new InvalidRules(id + ": unknown field '" + field + "' for " + site);
                if (!arg.path("value").isTextual()) throw new InvalidRules(id + ": `" + op + "` needs a string value");
                String value = arg.get("value").asText();
                if (!value.equals(value.toLowerCase(Locale.ROOT))) throw new InvalidRules(id + ": value '" + value + "' is not lower case");
                if (arg.size() != 2) throw new InvalidRules(id + ": `" + op + "` takes only field and value");
            }
            case "present" -> {
                if (!"mqtt_bridge".equals(site) || !arg.isTextual()) throw new InvalidRules(id + ": `present` is an MQTT key test");
            }
            case "capability" -> {
                if (!"zigbee2mqtt".equals(site) || !arg.isTextual()) throw new InvalidRules(id + ": `capability` is a Z2M test");
                try {
                    DeviceCapability.valueOf(arg.asText());
                } catch (IllegalArgumentException e) {
                    throw new InvalidRules(id + ": unknown capability '" + arg.asText() + "'");
                }
            }
            case "exposes_property" -> {
                if (!"zigbee2mqtt".equals(site) || !arg.isTextual()) throw new InvalidRules(id + ": `exposes_property` is a Z2M test");
            }
            case "exposes_relay" -> {
                if (!"zigbee2mqtt".equals(site) || !arg.isBoolean() || !arg.asBoolean()) throw new InvalidRules(id + ": `exposes_relay` takes true, Z2M only");
            }
            case "always" -> {
                if (!arg.isBoolean() || !arg.asBoolean()) throw new InvalidRules(id + ": `always` takes true");
            }
            default -> throw new InvalidRules(id + ": unknown operator '" + op + "'");
        }
    }

    // ---------------------------------------------------------------------------------------- evaluation

    private static boolean holds(JsonNode node, SiteInput input) {
        String op = node.fieldNames().next();
        JsonNode arg = node.get(op);
        return switch (op) {
            case "all" -> { for (JsonNode c : arg) if (!holds(c, input)) yield false; yield true; }
            case "any" -> { for (JsonNode c : arg) if (holds(c, input)) yield true; yield false; }
            case "not" -> !holds(arg, input);
            case "equals" -> input.field(arg.get("field").asText()).equals(arg.get("value").asText());
            case "contains" -> input.field(arg.get("field").asText()).contains(arg.get("value").asText());
            case "present" -> input.present(arg.asText());
            case "capability" -> input.capability(DeviceCapability.valueOf(arg.asText()));
            case "exposes_property" -> exposesProperty(input.exposes(), arg.asText());
            case "exposes_relay" -> exposesRelay(input.exposes());
            case "always" -> true;
            default -> throw new IllegalStateException(op);
        };
    }

    /** Z2M contract: recursive through features; property, else name; a `config` category does not count. */
    static boolean exposesProperty(JsonNode exposes, String name) {
        if (exposes == null || !exposes.isArray()) return false;
        for (JsonNode expose : exposes) {
            String property = expose.path("property").asText(expose.path("name").asText(""));
            if (property.equals(name) && !"config".equals(expose.path("category").asText(""))) return true;
            if (exposesProperty(expose.path("features"), name)) return true;
        }
        return false;
    }

    /** Z2M contract: a writable binary state (state or state_x) that is not config or diagnostic, never inside a
     *  light, lock, cover, fan or climate feature; recursive through features. Missing access counts as 1. */
    static boolean exposesRelay(JsonNode exposes) {
        if (exposes == null || !exposes.isArray()) return false;
        for (JsonNode expose : exposes) {
            String type = expose.path("type").asText("");
            if (NON_RELAY_EXPOSE_TYPES.contains(type)) continue;
            String category = expose.path("category").asText("");
            boolean writable = (expose.path("access").asInt(1) & 2) != 0;
            if ("binary".equals(type) && !"config".equals(category) && !"diagnostic".equals(category) && writable
                    && (relayKey(expose.path("name").asText("")) || relayKey(expose.path("property").asText("")))) {
                return true;
            }
            if (exposesRelay(expose.path("features"))) return true;
        }
        return false;
    }

    private static boolean relayKey(String key) {
        return key.equals("state") || key.matches("state_[a-z0-9]+");
    }

    /** One device's classifier input, with each field already extracted the way that site's inferType extracts it. */
    public interface SiteInput {
        String site();

        /** The extracted, comparison-ready value; never null. */
        String field(String name);

        default boolean present(String key) { return false; }

        default boolean capability(DeviceCapability capability) { return false; }

        default JsonNode exposes() { return null; }

        /** HA: domain is compared raw; attributes use String.valueOf(getOrDefault(key, "")) then Locale.ROOT lower case,
         *  so a missing key is "" and a present null is "null". */
        static SiteInput homeAssistant(String domain, Map<String, Object> attributes) {
            Map<String, Object> attrs = attributes == null ? Map.of() : attributes;
            return new SiteInput() {
                public String site() { return "home_assistant"; }

                public String field(String name) {
                    if ("domain".equals(name)) return String.valueOf(domain);
                    return String.valueOf(attrs.getOrDefault(name, "")).toLowerCase(Locale.ROOT);
                }
            };
        }

        /** Z2M: definition fields use asText("") (null, object and array become ""), then lower case; the friendly
         *  name is the raw string lower-cased (null is ""). Z2M's inferType lower-cases with the default locale; this
         *  uses Locale.ROOT, a documented discrepancy recorded by the tests rather than hidden. */
        static SiteInput zigbee(JsonNode definition, Set<DeviceCapability> capabilities, String friendlyName) {
            JsonNode def = definition == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : definition;
            Set<DeviceCapability> caps = capabilities == null ? Set.of() : capabilities;
            return new SiteInput() {
                public String site() { return "zigbee2mqtt"; }

                public String field(String name) {
                    if ("friendly_name".equals(name)) return Objects.toString(friendlyName, "").toLowerCase(Locale.ROOT);
                    return def.path(name).asText("").toLowerCase(Locale.ROOT);
                }

                public boolean capability(DeviceCapability capability) { return caps.contains(capability); }

                public JsonNode exposes() { return def.path("exposes"); }
            };
        }

        /** MQTT bridge: key presence only (a null-valued key is present); no payload value is read. */
        static SiteInput mqtt(Map<String, Object> data) {
            Map<String, Object> payload = data == null ? Map.of() : data;
            return new SiteInput() {
                public String site() { return "mqtt_bridge"; }

                public String field(String name) { return ""; }

                public boolean present(String key) { return payload.containsKey(key); }
            };
        }
    }
}
