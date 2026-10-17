package com.cabin.orchestrator.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The human-need vocabulary is written once (docs/ai-assistant/training/intent-slot/concepts.json) and repeated in the
 * intent-slot schema (which the backend bundles) and the ontology entity human_need_concept. This fails when one of
 * them is edited without the others, so the grading screen, the model's schema and the ontology cannot disagree.
 */
class HumanNeedVocabularyParityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static Path upwards(String relative) {
        Path cursor = Path.of("").toAbsolutePath();
        while (cursor != null) {
            Path candidate = cursor.resolve(relative);
            if (Files.exists(candidate)) return candidate;
            cursor = cursor.getParent();
        }
        return null;
    }

    private static List<String> concepts(JsonNode node) {
        List<String> ids = new ArrayList<>();
        node.forEach(n -> ids.add(n.asText()));
        return ids;
    }

    @Test
    @SuppressWarnings("unchecked")
    void theBundledSchemaTheOntologyEntityAndConceptsJsonListTheSameNeedsInTheSameOrder() throws IOException {
        Path concepts = upwards("docs/ai-assistant/training/intent-slot/concepts.json");
        Path ontology = upwards("docs/ontology.yaml");
        assumeTrue(concepts != null && ontology != null, "docs directory not present next to the backend");

        List<String> source = new ArrayList<>();
        JSON.readTree(concepts.toFile()).get("concepts").forEach(c -> source.add(c.get("id").asText()));

        try (InputStream in = getClass().getClassLoader().getResourceAsStream("assistant/intent-slot.schema.json")) {
            List<String> schema = concepts(JSON.readTree(in).path("properties").path("slots").path("properties").path("concept").path("enum"));
            assertEquals(source, schema, "the bundled schema's concept enum drifted from concepts.json");
        }

        try (InputStream in = Files.newInputStream(ontology)) {
            Map<String, Object> root = new Yaml().load(in);
            Map<String, Object> entity = ((List<Map<String, Object>>) root.get("elements")).stream()
                .filter(e -> "human_need_concept".equals(e.get("id"))).findFirst().orElseThrow();
            assertEquals(source, ((Map<String, List<String>>) entity.get("constraint")).get("enum"));
            List<String> described = new ArrayList<>();
            ((List<Map<String, Object>>) entity.get("concept_vocabulary")).forEach(c -> described.add((String) c.get("id")));
            assertEquals(source, described, "the ontology's concept_vocabulary drifted from concepts.json");
        }
        assertTrue(source.containsAll(List.of("light", "nap", "wake_up")));
        assertTrue(!source.contains("bright"), "bright is an alias of light, not its own concept");
    }
}
