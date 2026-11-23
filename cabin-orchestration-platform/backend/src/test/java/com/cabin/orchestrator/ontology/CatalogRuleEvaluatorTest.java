package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantDiscoveryService;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.mqtt.MqttBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Catalog S3b: the grammar in the shipped catalog against the REAL inferType methods (by reflection). The expected
 * type always comes from the classifier, never from the evaluator, so the two are independent. Synthetic inputs only.
 */
class CatalogRuleEvaluatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjenesisStd OBJENESIS = new ObjenesisStd();

    private static JsonNode catalog() throws Exception {
        try (InputStream in = CatalogRuleEvaluatorTest.class.getClassLoader().getResourceAsStream(DeviceCatalogService.CLASSPATH_COPY)) {
            assertNotNull(in, "image copy of the catalog");
            return JSON.readTree(in);
        }
    }

    /** One classifier input: site plus the JSON the parity test's fixtures use. */
    record Case(String label, String site, JsonNode input) {}

    static Case c(String label, String site, String json) {
        try {
            return new Case(label, site, JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalArgumentException(label, e);
        }
    }

    static List<Case> corpus(JsonNode catalog) {
        List<Case> cases = new ArrayList<>();
        catalog.get("match_rules").forEach(r -> cases.add(new Case("fixture " + r.get("id").asText(), r.get("site").asText(), r.get("input"))));
        String ha = "home_assistant", zb = "zigbee2mqtt", mb = "mqtt_bridge";
        // Home Assistant: near-misses, precedence, case, missing / null / odd types
        cases.addAll(List.of(
            c("ha domain upper case is not lock", ha, "{\"domain\":\"LOCK\",\"attributes\":{}}"),
            c("ha class upper case still smoke", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"device_class\":\"SMOKE\"}}"),
            c("ha near-miss smok", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"device_class\":\"smok\"}}"),
            c("ha smoke wins over co", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"device_class\":\"smoke_carbon_monoxide\"}}"),
            c("ha co exact", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"device_class\":\"co\"}}"),
            c("ha co2 is not co", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"co2\"}}"),
            c("ha carbon_dioxide contains no carbon_monoxide", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"carbon_dioxide\"}}"),
            c("ha lock domain beats class", ha, "{\"domain\":\"lock\",\"attributes\":{\"device_class\":\"smoke\"}}"),
            c("ha moisture vs humidity", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"moisture_humidity\"}}"),
            c("ha occupancy", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"device_class\":\"occupancy\"}}"),
            c("ha window opening", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"device_class\":\"opening\"}}"),
            c("ha power in binary domain", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"device_class\":\"power\"}}"),
            c("ha power_factor is not power", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"power_factor\"}}"),
            c("ha energy", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"energy\"}}"),
            c("ha aqi exact", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"aqi\"}}"),
            c("ha aqi_index not exact", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"aqi_index\"}}"),
            c("ha voc parts", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"volatile_organic_compounds_parts\"}}"),
            c("ha ppm upper case", ha, "{\"domain\":\"sensor\",\"attributes\":{\"unit_of_measurement\":\"PPM\"}}"),
            c("ha ppm in binary domain", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"unit_of_measurement\":\"ppm\"}}"),
            c("ha ppm with an unmatched class", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":\"pressure\",\"unit_of_measurement\":\"ppm\"}}"),
            c("ha co-level name", ha, "{\"domain\":\"sensor\",\"attributes\":{\"friendly_name\":\"Upstairs CO-Level\"}}"),
            c("ha co level name in binary domain", ha, "{\"domain\":\"binary_sensor\",\"attributes\":{\"friendly_name\":\"CO Level\"}}"),
            c("ha colevel near-miss", ha, "{\"domain\":\"sensor\",\"attributes\":{\"friendly_name\":\"colevel\"}}"),
            c("ha present null class", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":null}}"),
            c("ha numeric class", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":42}}"),
            c("ha map class containing smoke", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":{\"smoke\":1}}}"),
            c("ha list class containing door", ha, "{\"domain\":\"sensor\",\"attributes\":{\"device_class\":[\"door\"]}}"),
            c("ha null unit", ha, "{\"domain\":\"sensor\",\"attributes\":{\"unit_of_measurement\":null}}"),
            c("ha no attributes", ha, "{\"domain\":\"switch\",\"attributes\":{}}")));
        // Zigbee2MQTT
        String relay = "{\"type\":\"binary\",\"name\":\"state\",\"property\":\"state\",\"access\":7}";
        cases.addAll(List.of(
            c("zb snzb-05 lower", zb, "{\"definition\":{\"model\":\"snzb-05\",\"description\":\"x\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb near-miss snzb-0", zb, "{\"definition\":{\"model\":\"SNZB-0\",\"description\":\"x\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb leak beats motion", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Water leak and motion\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb valve in name with relay", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Actuator\",\"exposes\":[" + relay + "]},\"caps\":[],\"friendly_name\":\"Main VALVE\"}"),
            c("zb valve without relay", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Valve controller\",\"exposes\":[]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb valve read-only state", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Valve\",\"exposes\":[{\"type\":\"binary\",\"name\":\"state\",\"access\":1}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb relay with leak expose is not a switch", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[" + relay + ",{\"property\":\"water_leak\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb relay with smoke expose", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[" + relay + ",{\"property\":\"smoke\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb relay with config smoke expose", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[" + relay + ",{\"property\":\"smoke\",\"category\":\"config\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb state_l1 relay", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[{\"type\":\"binary\",\"name\":\"state_l1\",\"access\":7}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb state_L1 not a relay key", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[{\"type\":\"binary\",\"name\":\"state_L1\",\"access\":7}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb relay inside a light", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[{\"type\":\"light\",\"features\":[" + relay + "]}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb relay inside a switch feature", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[{\"type\":\"switch\",\"features\":[" + relay + "]}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb diagnostic relay", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[{\"type\":\"binary\",\"name\":\"state\",\"access\":7,\"category\":\"diagnostic\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb relay with missing access", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[{\"type\":\"binary\",\"name\":\"state\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb relay with text access", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Unit\",\"exposes\":[{\"type\":\"binary\",\"name\":\"state\",\"access\":\"7\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb power-monitoring valve relay: valve rule first", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Valve relay\",\"exposes\":[" + relay + "]},\"caps\":[\"POWER_MONITOR\"],\"friendly_name\":\"a\"}"),
            c("zb power monitor switch", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Smart switch\"},\"caps\":[\"POWER_MONITOR\"],\"friendly_name\":\"a\"}"),
            c("zb power monitor without wording", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Meter\"},\"caps\":[\"POWER_MONITOR\"],\"friendly_name\":\"a\"}"),
            c("zb switch description", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Wall Switch\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb zbminir model", zb, "{\"definition\":{\"model\":\"ZBMINIR2\",\"description\":\"x\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb smoke vs co alarm", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"CO alarm\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb smart plug", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Smart plug\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb plug without smart", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"Plug\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb climate and alarm caps", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"x\"},\"caps\":[\"ALARM\",\"CLIMATE\"],\"friendly_name\":\"a\"}"),
            c("zb access and alarm caps", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"x\"},\"caps\":[\"ALARM\",\"ACCESS_CONTROL\"],\"friendly_name\":\"a\"}"),
            c("zb occupancy by name", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"x\",\"exposes\":[{\"name\":\"occupancy\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb config occupancy", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"x\",\"exposes\":[{\"property\":\"occupancy\",\"category\":\"config\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb nested humidity", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"x\",\"exposes\":[{\"type\":\"composite\",\"features\":[{\"property\":\"humidity\"}]}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb contact and temperature", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"x\",\"exposes\":[{\"property\":\"temperature\"},{\"property\":\"contact\"}]},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb exposes is an object", zb, "{\"definition\":{\"model\":\"Q\",\"description\":\"x\",\"exposes\":{\"property\":\"occupancy\"}},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb description object containing switch", zb, "{\"definition\":{\"model\":\"Q\",\"description\":{\"text\":\"switch\"}},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb description null", zb, "{\"definition\":{\"model\":\"Q\",\"description\":null},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb numeric model", zb, "{\"definition\":{\"model\":5,\"description\":\"x\"},\"caps\":[],\"friendly_name\":\"a\"}"),
            c("zb empty definition", zb, "{\"definition\":{},\"caps\":[],\"friendly_name\":\"a\"}")));
        // MQTT bridge
        cases.addAll(List.of(
            c("mb null-valued key is present", mb, "{\"data\":{\"locked\":null}}"),
            c("mb psi beats temp_f", mb, "{\"data\":{\"temp_f\":70,\"psi\":40}}"),
            c("mb alarm beats locked", mb, "{\"data\":{\"locked\":true,\"alarm\":true}}"),
            c("mb near-miss Motion", mb, "{\"data\":{\"Motion\":true}}"),
            c("mb unrelated keys", mb, "{\"data\":{\"battery\":90,\"temp_c\":20}}")));
        return cases;
    }

    static DeviceType real(Case k) throws Exception {
        JsonNode in = k.input();
        switch (k.site()) {
            case "home_assistant": {
                Method m = HomeAssistantDiscoveryService.class.getDeclaredMethod("inferType", String.class, Map.class);
                m.setAccessible(true);
                return (DeviceType) m.invoke(OBJENESIS.newInstance(HomeAssistantDiscoveryService.class), in.get("domain").asText(), attrs(in));
            }
            case "zigbee2mqtt": {
                Method m = Zigbee2MqttAdapter.class.getDeclaredMethod("inferType", JsonNode.class, Set.class, String.class);
                m.setAccessible(true);
                return (DeviceType) m.invoke(OBJENESIS.newInstance(Zigbee2MqttAdapter.class), in.get("definition"), caps(in), in.get("friendly_name").asText());
            }
            default: {
                Method m = MqttBridgeService.class.getDeclaredMethod("inferType", Map.class);
                m.setAccessible(true);
                return (DeviceType) m.invoke(OBJENESIS.newInstance(MqttBridgeService.class), data(in));
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> attrs(JsonNode in) {
        return JSON.convertValue(in.get("attributes"), LinkedHashMap.class);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(JsonNode in) {
        return JSON.convertValue(in.get("data"), LinkedHashMap.class);
    }

    static Set<DeviceCapability> caps(JsonNode in) {
        Set<DeviceCapability> caps = EnumSet.noneOf(DeviceCapability.class);
        in.get("caps").forEach(x -> caps.add(DeviceCapability.valueOf(x.asText())));
        return caps;
    }

    static CatalogRuleEvaluator.SiteInput siteInput(Case k) {
        JsonNode in = k.input();
        return switch (k.site()) {
            case "home_assistant" -> CatalogRuleEvaluator.SiteInput.homeAssistant(in.get("domain").asText(), attrs(in));
            case "zigbee2mqtt" -> CatalogRuleEvaluator.SiteInput.zigbee(in.get("definition"), caps(in), in.get("friendly_name").asText());
            default -> CatalogRuleEvaluator.SiteInput.mqtt(data(in));
        };
    }

    /** Every case where the grammar and the real classifier disagree. */
    static List<String> disagreements(CatalogRuleEvaluator evaluator, List<Case> cases) throws Exception {
        List<String> out = new ArrayList<>();
        for (Case k : cases) {
            DeviceType expected = real(k);
            CatalogRuleEvaluator.Match got = evaluator.evaluate(siteInput(k));
            if (got.type() != expected) out.add(k.label() + ": grammar " + got.ruleId() + "=" + got.type() + ", inferType " + expected);
        }
        return out;
    }

    @Test
    void theGrammarAgreesWithTheRealClassifiersOnEveryCase() throws Exception {
        JsonNode catalog = catalog();
        List<Case> cases = corpus(catalog);
        assertTrue(cases.size() > 100, "corpus size " + cases.size());
        assertEquals(List.of(), disagreements(CatalogRuleEvaluator.load(catalog.get("match_rules")), cases));
    }

    @Test
    void eachFixtureIsMatchedByItsOwnRule() throws Exception {
        JsonNode catalog = catalog();
        CatalogRuleEvaluator evaluator = CatalogRuleEvaluator.load(catalog.get("match_rules"));
        for (JsonNode rule : catalog.get("match_rules")) {
            Case k = new Case(rule.get("id").asText(), rule.get("site").asText(), rule.get("input"));
            assertEquals(rule.get("id").asText(), evaluator.evaluate(siteInput(k)).ruleId(), "fixture of " + k.label());
        }
    }

    @Test
    void onlyTheNameWorkaroundIsTagged() throws Exception {
        CatalogRuleEvaluator evaluator = CatalogRuleEvaluator.load(catalog().get("match_rules"));
        List<String> tagged = new ArrayList<>();
        CatalogRuleEvaluator.SITES.forEach(s -> evaluator.rules(s).stream().filter(CatalogRuleEvaluator.Rule::workaround).forEach(r -> tagged.add(r.id())));
        assertEquals(List.of("ha-15-name-co-level"), tagged);
    }

    /** The documented discrepancy (S3b design, section 2): Zigbee2MqttAdapter.inferType lower-cases with the default
     *  locale, the grammar with Locale.ROOT. Under a Turkish default locale "MOTION" lower-cases to "motıon" in the
     *  classifier, so the two disagree. This test keeps that finding visible; it does not change production code. */
    @Test
    void theDefaultLocaleDiscrepancyIsVisibleUnderATurkishLocale() throws Exception {
        CatalogRuleEvaluator evaluator = CatalogRuleEvaluator.load(catalog().get("match_rules"));
        Case motion = c("zb MOTION upper case", "zigbee2mqtt",
            "{\"definition\":{\"model\":\"Q\",\"description\":\"MOTION\"},\"caps\":[],\"friendly_name\":\"a\"}");
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            assertEquals(List.of(), disagreements(evaluator, List.of(motion)), "agree under the build's own locale");
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertEquals(1, disagreements(evaluator, List.of(motion)).size(),
                "under tr-TR the classifier misses MOTION and the grammar does not: the documented finding");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    // ---------------------------------------------------------------- validation

    static JsonNode mutated(Consumer<ArrayNode> change) throws Exception {
        ArrayNode rules = (ArrayNode) catalog().get("match_rules").deepCopy();
        change.accept(rules);
        return rules;
    }

    static ObjectNode rule(ArrayNode rules, String id) {
        for (JsonNode r : rules) if (r.get("id").asText().equals(id)) return (ObjectNode) r;
        throw new IllegalArgumentException(id);
    }

    @Test
    void malformedRuleSetsAreRefused() throws Exception {
        List<Consumer<ArrayNode>> bad = List.of(
            rules -> rule(rules, "ha-04-smoke").set("when", JSON.createObjectNode().put("startswith", "x")),
            rules -> ((ObjectNode) rule(rules, "ha-04-smoke").get("when").get("contains")).put("field", "colour"),
            rules -> rule(rules, "ha-04-smoke").remove("when"),
            rules -> rule(rules, "ha-05-carbon-monoxide-alarm").put("id", "ha-04-smoke"),
            rules -> ((ObjectNode) rule(rules, "ha-04-smoke").get("when").get("contains")).put("value", "Smoke"),
            rules -> rule(rules, "ha-04-smoke").set("when", JSON.createObjectNode().put("always", true)),
            rules -> rule(rules, "ha-16-default").set("when", rule(rules, "ha-06-moisture").get("when").deepCopy()),
            rules -> rule(rules, "ha-04-smoke").set("when", JSON.createObjectNode().put("present", "x")),
            rules -> rule(rules, "zb-10-climate-capability").set("when", JSON.createObjectNode().put("capability", "TELEPORT")),
            rules -> rule(rules, "ha-07-motion-or-occupancy").set("when", JSON.createObjectNode().set("any", JSON.createArrayNode())));
        int i = 0;
        for (Consumer<ArrayNode> change : bad) {
            JsonNode rules = mutated(change);
            int n = i++;
            assertThrows(CatalogRuleEvaluator.InvalidRules.class, () -> CatalogRuleEvaluator.load(rules), "malformed case " + n);
        }
    }

    // ---------------------------------------------------------------- mutants: the comparison must catch them

    @Test
    void safetyAndPrecedenceMutantsAreCaughtByTheComparison() throws Exception {
        List<Case> cases = corpus(catalog());
        Map<String, Consumer<ArrayNode>> mutants = new LinkedHashMap<>();
        mutants.put("leak ha-06 moisture -> moistur", r -> ((ObjectNode) rule(r, "ha-06-moisture").get("when").get("contains")).put("value", "moisturex"));
        mutants.put("leak zb-01 drops the description test", r -> rule(r, "zb-01-snzb-05-or-water-leak").set("when", rule(r, "zb-01-snzb-05-or-water-leak").get("when").get("any").get(0).deepCopy()));
        mutants.put("valve zb-05 drops the relay test", r -> rule(r, "zb-05-valve-actuator").set("when", rule(r, "zb-05-valve-actuator").get("when").get("all").get(0).deepCopy()));
        mutants.put("lock ha-01 domain lock -> locks", r -> ((ObjectNode) rule(r, "ha-01-lock").get("when").get("equals")).put("value", "locks"));
        mutants.put("lock zb-11 capability -> CLIMATE", r -> rule(r, "zb-11-access-control-capability").set("when", JSON.createObjectNode().put("capability", "ALARM")));
        mutants.put("lock mb-04 key -> lock", r -> rule(r, "mb-04-locked").set("when", JSON.createObjectNode().put("present", "lock")));
        mutants.put("co ha-05 drops the exact co", r -> rule(r, "ha-05-carbon-monoxide-alarm").set("when", rule(r, "ha-05-carbon-monoxide-alarm").get("when").get("any").get(0).deepCopy()));
        mutants.put("precedence: ha-05 before ha-04", r -> swap(r, "ha-04-smoke", "ha-05-carbon-monoxide-alarm"));
        mutants.put("precedence: zb-06 before zb-05", r -> swap(r, "zb-05-valve-actuator", "zb-06-smart-switch"));
        mutants.put("precedence: mb-04 before mb-03", r -> swap(r, "mb-03-alarm", "mb-04-locked"));
        mutants.put("precedence: zb-12 before zb-10", r -> swap(r, "zb-10-climate-capability", "zb-12-alarm-capability"));
        mutants.put("contains -> equals on ha-04", r -> {
            ObjectNode when = (ObjectNode) rule(r, "ha-04-smoke").get("when");
            when.set("equals", when.remove("contains"));
        });
        List<String> survivors = new ArrayList<>();
        for (Map.Entry<String, Consumer<ArrayNode>> m : mutants.entrySet()) {
            if (disagreements(CatalogRuleEvaluator.load(mutated(m.getValue())), cases).isEmpty()) survivors.add(m.getKey());
        }
        System.out.println("S3b grammar mutants killed: " + (mutants.size() - survivors.size()) + "/" + mutants.size());
        assertEquals(List.of(), survivors, "mutants the independent comparison did not catch");
    }

    static void swap(ArrayNode rules, String a, String b) {
        int i = -1, j = -1;
        for (int k = 0; k < rules.size(); k++) {
            if (rules.get(k).get("id").asText().equals(a)) i = k;
            if (rules.get(k).get("id").asText().equals(b)) j = k;
        }
        JsonNode x = rules.get(i);
        rules.set(i, rules.get(j));
        rules.set(j, x);
    }
}
