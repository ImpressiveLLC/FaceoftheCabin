package com.cabin.orchestrator.ontology;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OntologyYamlTest {

    @Test
    @SuppressWarnings("unchecked")
    void ontologyParsesWithUniqueIdsAndIncludesAlertTruthEntities() throws Exception {
        Path ontology = findOntology();
        try (InputStream input = Files.newInputStream(ontology)) {
            Map<String, Object> root = new Yaml().load(input);
            List<Map<String, Object>> elements = (List<Map<String, Object>>) root.get("elements");
            assertNotNull(elements);

            Set<String> ids = new HashSet<>();
            for (Map<String, Object> element : elements) {
                String id = String.valueOf(element.get("id"));
                assertTrue(ids.add(id), "duplicate ontology id: " + id);
            }
            assertEquals(elements.size(), ids.size());
            assertTrue(ids.contains("active_alert_condition"));
            assertTrue(ids.contains("automation_rule_status"));
        }
    }

    private Path findOntology() {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve("docs").resolve("ontology.yaml");
            if (Files.isRegularFile(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        throw new IllegalStateException("Could not find docs/ontology.yaml from " + Path.of("").toAbsolutePath());
    }
}
