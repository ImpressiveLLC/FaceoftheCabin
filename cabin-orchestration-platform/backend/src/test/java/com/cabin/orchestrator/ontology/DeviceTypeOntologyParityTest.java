package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.devices.model.D7MeasurementTypes;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Slice S1 of the "ontology drives device adds" roadmap
 * (docs/governance/proposals/2026-11-14-ontology-drives-device-adds-roadmap.md): a drift guard
 * between docs/ontology.yaml and the Java DeviceType enum. It changes no behavior.
 *
 * The two were tied together by nothing but comments. This test follows the pattern of
 * D7MeasurementTypesTest: it fails when a device type is named on one side and missing on the
 * other, and the only way to accept a known difference is to list it below with a reason. An
 * allowlist entry that stops being true also fails, so the list cannot go stale.
 */
class DeviceTypeOntologyParityTest {

    /**
     * Device types the ontology names in an applies_to_device_type list that the DeviceType enum does
     * not have. Each is a known, accepted difference with the reason it exists.
     */
    private static final Map<String, String> KNOWN_ONTOLOGY_ONLY = Map.of(
        "RF_TRIPWIRE",
        "trigger_rf_tripwire_crossed is a candidate trigger for a device type that is not built yet; "
            + "the enum gains the constant when that device is enrolled");

    @Test
    void everyDeviceTypeTheOntologyNamesIsADeviceTypeExceptTheKnownDifferences() throws Exception {
        Set<String> referenced = ontologyDeviceTypeReferences();
        Set<String> constants = Arrays.stream(DeviceType.values()).map(Enum::name).collect(Collectors.toSet());

        Set<String> unexpected = new LinkedHashSet<>(referenced);
        unexpected.removeAll(constants);
        unexpected.removeAll(KNOWN_ONTOLOGY_ONLY.keySet());

        assertTrue(unexpected.isEmpty(),
            "docs/ontology.yaml names device types that DeviceType does not have: " + unexpected
                + ". Add the constant, correct the ontology, or list it in KNOWN_ONTOLOGY_ONLY with a reason.");
    }

    @Test
    void theKnownDifferencesAreStillDifferences() throws Exception {
        Set<String> referenced = ontologyDeviceTypeReferences();
        Set<String> constants = Arrays.stream(DeviceType.values()).map(Enum::name).collect(Collectors.toSet());

        for (String known : KNOWN_ONTOLOGY_ONLY.keySet()) {
            assertTrue(referenced.contains(known),
                known + " is no longer named in docs/ontology.yaml; remove it from KNOWN_ONTOLOGY_ONLY");
            assertTrue(!constants.contains(known),
                known + " is now a DeviceType constant; remove it from KNOWN_ONTOLOGY_ONLY");
        }
    }

    @Test
    void everyFieldADeviceTypeReportsIsARecognizedMeasurementType() {
        Map<String, Set<String>> unrecognized = new LinkedHashMap<>();
        for (DeviceType type : DeviceType.values()) {
            for (String field : type.telemetryFields()) {
                if (D7MeasurementTypes.toMeasurementType(field).isEmpty()) {
                    unrecognized.computeIfAbsent(type.name(), k -> new LinkedHashSet<>()).add(field);
                }
            }
        }
        assertEquals(Map.of(), unrecognized,
            "DeviceType.telemetryFields() reports fields that are not in the D7 measurement vocabulary");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> ontologyDeviceTypeReferences() throws Exception {
        Path ontology = findOntology();
        assumeTrue(ontology != null, "docs/ontology.yaml not present next to the backend");
        Set<String> referenced = new LinkedHashSet<>();
        try (InputStream input = Files.newInputStream(ontology)) {
            Map<String, Object> root = new Yaml().load(input);
            List<Map<String, Object>> elements = (List<Map<String, Object>>) root.get("elements");
            for (Map<String, Object> element : elements) {
                Object applies = element.get("applies_to_device_type");
                if (applies instanceof List<?> list) {
                    for (Object value : list) referenced.add(String.valueOf(value));
                }
            }
        }
        assertTrue(!referenced.isEmpty(), "expected the ontology to name at least one device type");
        return referenced;
    }

    private static Path findOntology() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("docs").resolve("ontology.yaml");
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return null;
    }
}
