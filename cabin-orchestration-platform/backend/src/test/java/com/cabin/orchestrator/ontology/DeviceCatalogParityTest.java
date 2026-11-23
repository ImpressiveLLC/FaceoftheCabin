package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantDiscoveryService;
import com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter;
import com.cabin.orchestrator.mqtt.MqttBridgeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.ObjenesisStd;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Slice S2 of the "ontology drives device adds" roadmap, design #475 section 5: the parity test that ties the generated
 * device catalog (docs/ontology/device-catalog.json) to the Java side, in both directions. It extends the S1 test
 * (DeviceTypeOntologyParityTest, ontology to enum) and changes no behavior.
 *
 * Checks 1 to 6 below. Check 7 (a golden table of today's per-device types for the live inventory) is NOT_RUN: it needs
 * a committed inventory snapshot that does not exist, and the roadmap leaves live-data disagreements to the S3 shadow read.
 *
 * The match-rule fixtures (docs/ontology/catalog-seed.yaml) are run through the REAL inferType methods by reflection, so a
 * rule cannot drift from the code, and a method with more `return DeviceType.X` branches than rules fails.
 */
class DeviceCatalogParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Set to "true" by the ontology-catalog-gate workflow, where the docs and sources are always checked out: a missing
     * input there is a broken gate and must FAIL, not skip (Cowork 7075465576 item c). Elsewhere (for example a backend
     * image build whose context holds only the backend) a missing input still skips, as before.
     */
    static final String REQUIRE_INPUTS_ENV = "REQUIRE_ONTOLOGY_DOCS";

    /** Reads the environment; replaceable in tests so both branches can be proven. */
    static java.util.function.Supplier<String> envReader = () -> System.getenv(REQUIRE_INPUTS_ENV);

    static boolean inputsRequired() {
        return "true".equalsIgnoreCase(envReader.get());
    }

    /** Fails when {@link #inputsRequired()}, otherwise skips, when {@code present} is false. */
    static void requirePresent(boolean present, String message) {
        if (inputsRequired()) {
            assertTrue(present, message + " (" + REQUIRE_INPUTS_ENV + "=true: the gate must not skip)");
        } else {
            assumeTrue(present, message);
        }
    }

    private static Path findUp(String... parts) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor;
            for (String part : parts) candidate = candidate.resolve(part);
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return null;
    }

    private static JsonNode catalog() throws Exception {
        Path path = findUp("docs", "ontology", "device-catalog.json");
        requirePresent(path != null, "docs/ontology/device-catalog.json not present next to the backend");
        return JSON.readTree(Files.readAllBytes(path));
    }

    private static Map<String, JsonNode> entriesById(JsonNode catalog) {
        Map<String, JsonNode> byId = new TreeMap<>();
        for (JsonNode entry : catalog.get("entries")) {
            assertTrue(byId.put(entry.get("id").asText(), entry) == null, "duplicate catalog entry " + entry.get("id"));
        }
        return byId;
    }

    private static Set<String> constants() {
        return Arrays.stream(DeviceType.values()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
    }

    // ---- check 1: enum to catalog
    @Test
    void everyDeviceTypeConstantHasACatalogEntry() throws Exception {
        Map<String, JsonNode> byId = entriesById(catalog());
        Set<String> missing = constants();
        missing.removeAll(byId.keySet());
        assertTrue(missing.isEmpty(), "DeviceType constants with no catalog entry: " + missing
            + ". Run python scripts/ontology/generate_catalog.py and commit the result.");
        for (String name : constants()) {
            assertEquals("enum", byId.get(name).get("status").asText(), name + " must be an enum-status entry");
        }
    }

    // ---- check 2: catalog to enum, with the ontology-only allowlist (each entry needs a reason and must stay true)
    @Test
    void everyCatalogEntryIsADeviceTypeExceptTheListedOntologyOnlyTypes() throws Exception {
        Set<String> constants = constants();
        for (JsonNode entry : catalog().get("entries")) {
            String id = entry.get("id").asText();
            if ("ontology_only".equals(entry.get("status").asText())) {
                assertFalse(constants.contains(id), id + " is now a DeviceType constant; remove it from ontology_only in the seed");
                assertFalse(entry.get("reason").asText("").isBlank(), id + " is ontology_only and needs a reason");
            } else {
                assertTrue(constants.contains(id), "catalog entry " + id + " is not a DeviceType constant");
            }
        }
    }

    // ---- check 3: ontology to catalog
    @Test
    void everyDeviceTypeTheOntologyNamesHasACatalogEntry() throws Exception {
        Map<String, JsonNode> byId = entriesById(catalog());
        Set<String> missing = new TreeSet<>(ontologyReferences().keySet());
        missing.removeAll(byId.keySet());
        assertTrue(missing.isEmpty(), "docs/ontology.yaml names device types with no catalog entry: " + missing);
    }

    // ---- check 4: catalog to ontology (the ontology-owned field must equal the ontology; catches a hand edit)
    @Test
    void theTriggersEachEntryNamesEqualTheOntology() throws Exception {
        Map<String, Set<String>> fromOntology = ontologyReferences();
        for (JsonNode entry : catalog().get("entries")) {
            String id = entry.get("id").asText();
            Set<String> inCatalog = new TreeSet<>();
            entry.get("triggers_naming_type").forEach(n -> inCatalog.add(n.asText()));
            assertEquals(fromOntology.getOrDefault(id, Set.of()), inCatalog,
                id + ": triggers_naming_type differs from docs/ontology.yaml; regenerate the catalog");
        }
    }

    // ---- check 5: DeviceType.telemetryFields() and category to catalog
    @Test
    void reportedFieldsAndCategoryMatchTheEnum() throws Exception {
        Map<String, JsonNode> byId = entriesById(catalog());
        for (DeviceType type : DeviceType.values()) {
            JsonNode entry = byId.get(type.name());
            assertNotNull(entry, type.name());
            Set<String> fields = new TreeSet<>();
            entry.get("reported_fields").forEach(n -> fields.add(n.asText()));
            assertEquals(new TreeSet<>(type.telemetryFields()), fields, type.name() + ": reported_fields differ from telemetryFields()");
            assertEquals(type.category().name(), entry.get("category").asText(), type.name() + ": category differs from the enum");
        }
    }

    // ---- check 6: every inferType branch maps to a rule, and every rule's fixture gives the rule's type in the real code
    @Test
    void everyMatchRuleFixtureGivesItsTypeInTheRealInferTypeMethod() throws Exception {
        Map<String, Integer> ruleCount = new TreeMap<>();
        for (JsonNode rule : catalog().get("match_rules")) {
            String site = rule.get("site").asText();
            ruleCount.merge(site, 1, Integer::sum);
            DeviceType expected = DeviceType.valueOf(rule.get("returns").asText());
            DeviceType actual = runInferType(site, rule.get("input"));
            assertEquals(expected, actual, "rule " + rule.get("id").asText() + " (" + rule.get("describes").asText() + ")");
        }
        Map<String, Integer> branches = new TreeMap<>();
        branches.put("home_assistant", returnStatements("integrations/homeassistant/HomeAssistantDiscoveryService.java", "private DeviceType inferType("));
        branches.put("zigbee2mqtt", returnStatements("integrations/zigbee/Zigbee2MqttAdapter.java", "private DeviceType inferType("));
        branches.put("mqtt_bridge", returnStatements("mqtt/MqttBridgeService.java", "private DeviceType inferType("));
        assertEquals(branches, ruleCount,
            "an inferType method has a different number of `return DeviceType.X` branches than the catalog has rules; add or remove a rule in docs/ontology/catalog-seed.yaml");
    }

    // ---- check-in windows: the seed must equal DeviceHealthMonitor's constants and per-type switch
    @Test
    void checkinWindowsEqualDeviceHealthMonitor() throws Exception {
        Path source = findUp("cabin-orchestration-platform", "backend", "src", "main", "java", "com", "cabin", "orchestrator", "devices", "DeviceHealthMonitor.java");
        if (source == null) source = Path.of("src/main/java/com/cabin/orchestrator/devices/DeviceHealthMonitor.java");
        requirePresent(Files.isRegularFile(source), "DeviceHealthMonitor.java not found");
        String text = Files.readString(source);
        int zigbee = durationMinutes(text, "STALE_ZIGBEE");
        int battery = durationMinutes(text, "STALE_ZIGBEE_BATTERY");
        int camera = durationMinutes(text, "STALE_CAMERA");
        int ha = durationMinutes(text, "STALE_HA");
        int fallback = durationMinutes(text, "STALE_DEFAULT");
        Matcher caseBlock = Pattern.compile("case CAMERA -> STALE_CAMERA;\\s*case ([A-Z_,\\s]+?) -> STALE_HA;").matcher(text);
        assertTrue(caseBlock.find(), "the per-type check-in switch in DeviceHealthMonitor.staleThresholdFor changed shape; update the seed and this test");
        Set<String> haTypes = new TreeSet<>();
        for (String name : caseBlock.group(1).split(",")) haTypes.add(name.trim());
        for (JsonNode entry : catalog().get("entries")) {
            JsonNode minutes = entry.get("checkin_minutes");
            if (minutes == null || minutes.isNull()) continue;
            String id = entry.get("id").asText();
            int expected = "CAMERA".equals(id) ? camera : haTypes.contains(id) ? ha : fallback;
            assertEquals(expected, minutes.get("non_zigbee").asInt(), id + ": non-Zigbee check-in window");
            assertEquals(zigbee, minutes.get("zigbee_mains").asInt(), id + ": mains Zigbee window");
            assertEquals(battery, minutes.get("zigbee_battery").asInt(), id + ": battery Zigbee window");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static int durationMinutes(String source, String constant) {
        Matcher m = Pattern.compile("Duration " + constant + "\\s*=\\s*Duration\\.of(Minutes|Hours)\\((\\d+)\\)").matcher(source);
        assertTrue(m.find(), constant + " not found in DeviceHealthMonitor.java");
        int n = Integer.parseInt(m.group(2));
        return "Hours".equals(m.group(1)) ? n * 60 : n;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> ontologyReferences() throws Exception {
        Path ontology = findUp("docs", "ontology.yaml");
        requirePresent(ontology != null, "docs/ontology.yaml not present next to the backend");
        Map<String, Set<String>> refs = new TreeMap<>();
        try (InputStream input = Files.newInputStream(ontology)) {
            Map<String, Object> root = new Yaml().load(input);
            for (Map<String, Object> element : (List<Map<String, Object>>) root.get("elements")) {
                Object applies = element.get("applies_to_device_type");
                if (applies instanceof List<?> list) {
                    for (Object type : list) refs.computeIfAbsent(String.valueOf(type), k -> new TreeSet<>()).add(String.valueOf(element.get("id")));
                }
            }
        }
        return refs;
    }

    private static int returnStatements(String relativeSource, String signature) throws Exception {
        Path root = findSourceRoot();
        Path path = root.resolve(relativeSource);
        assertTrue(Files.isRegularFile(path), path + " not found");
        String text = Files.readString(path);
        int start = text.indexOf(signature);
        assertTrue(start >= 0, signature + " not found in " + relativeSource);
        int open = text.indexOf('{', start);
        int depth = 0;
        int end = open;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i; break; } }
        }
        Matcher m = Pattern.compile("return DeviceType\\.[A-Z0-9_]+;").matcher(text.substring(open, end));
        int count = 0;
        while (m.find()) count++;
        return count;
    }

    private static Path findSourceRoot() {
        Path module = Path.of("src/main/java/com/cabin/orchestrator");
        if (Files.isDirectory(module)) return module;
        Path found = findUp("cabin-orchestration-platform", "backend", "src", "main", "java", "com", "cabin", "orchestrator", "devices", "model", "DeviceType.java");
        requirePresent(found != null, "backend source root not found");
        return found.getParent().getParent().getParent();
    }

    private static DeviceType runInferType(String site, JsonNode input) throws Exception {
        ObjenesisStd objenesis = new ObjenesisStd();
        switch (site) {
            case "home_assistant": {
                Method method = HomeAssistantDiscoveryService.class.getDeclaredMethod("inferType", String.class, Map.class);
                method.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> attrs = JSON.convertValue(input.get("attributes"), LinkedHashMap.class);
                return (DeviceType) method.invoke(objenesis.newInstance(HomeAssistantDiscoveryService.class), input.get("domain").asText(), attrs);
            }
            case "zigbee2mqtt": {
                Method method = Zigbee2MqttAdapter.class.getDeclaredMethod("inferType", JsonNode.class, Set.class, String.class);
                method.setAccessible(true);
                Set<DeviceCapability> caps = EnumSet.noneOf(DeviceCapability.class);
                input.get("caps").forEach(c -> caps.add(DeviceCapability.valueOf(c.asText())));
                return (DeviceType) method.invoke(objenesis.newInstance(Zigbee2MqttAdapter.class), input.get("definition"), caps, input.get("friendly_name").asText());
            }
            case "mqtt_bridge": {
                Method method = MqttBridgeService.class.getDeclaredMethod("inferType", Map.class);
                method.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Object> data = JSON.convertValue(input.get("data"), LinkedHashMap.class);
                return (DeviceType) method.invoke(objenesis.newInstance(MqttBridgeService.class), data);
            }
            default:
                throw new IllegalArgumentException("unknown match-rule site " + site);
        }
    }

}
