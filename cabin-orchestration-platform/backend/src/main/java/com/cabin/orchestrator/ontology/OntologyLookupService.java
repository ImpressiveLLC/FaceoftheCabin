package com.cabin.orchestrator.ontology;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves raw docs/ontology.yaml entity ids into non-technical-safe
 * display info -- exists specifically so the Opportunity Map (and any
 * future ontology-facing UI, e.g. the still-unbuilt data dictionary
 * panel from docs/PRODUCT_NOTES.md's 2026-07-26 build order) never has
 * to leak a snake_case entity id to a Mom/Tech-Analyst-tier audience,
 * per that same doc's Name Management Decision.
 *
 * Reads the file fresh on every lookup rather than caching -- ontology.yaml
 * is a few thousand lines and this platform is family-scale traffic, so
 * the simplicity of "always current" beats a cache-invalidation scheme
 * nobody needs yet. Uses SnakeYAML directly (already on the classpath as
 * a transitive Spring Boot dependency -- it's what parses application.yml
 * itself -- no new pom.xml dependency needed).
 *
 * **Known gap**: cabin-backend's Docker build context is `../backend`
 * only (see infra/docker-compose.m920q.yml), so docs/ is NOT baked into
 * the image. This service depends on docs/ being bind-mounted into the
 * running container at cabin.ontology.path -- wired up for cabin-backend
 * in docker-compose.m920q.yml. If the mount is missing (e.g. a fresh
 * fork that hasn't set this up yet), lookups degrade gracefully to a
 * best-effort humanized id (snake_case -> Title Case) rather than
 * failing the whole request -- see fallback() below. `found: false` on
 * the response is the honest signal that this happened.
 */
@Service
public class OntologyLookupService {

    @Value("${cabin.ontology.path:/app/docs/ontology.yaml}")
    private String ontologyPath;

    public List<OntologyEntitySummary> lookup(List<String> ids) {
        Map<String, Map<String, Object>> byId = parseElementsById();
        return ids.stream().map(id -> {
            Map<String, Object> el = byId.get(id);
            if (el == null) return fallback(id);
            return new OntologyEntitySummary(
                id,
                Objects.toString(el.get("ui_display_name"), humanize(id)),
                el.get("entity_type") != null ? el.get("entity_type").toString() : null,
                true);
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseElementsById() {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        try (InputStream in = Files.newInputStream(Path.of(ontologyPath))) {
            Map<String, Object> root = new Yaml().load(in);
            List<Map<String, Object>> elements = (List<Map<String, Object>>) root.get("elements");
            if (elements != null) {
                for (Map<String, Object> el : elements) {
                    Object elId = el.get("id");
                    if (elId != null) byId.put(elId.toString(), el);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Missing mount, unparsable file, etc. -- degrade, do not 500.
            // Every id simply misses the map and falls back below.
        }
        return byId;
    }

    private OntologyEntitySummary fallback(String id) {
        return new OntologyEntitySummary(id, humanize(id), null, false);
    }

    private String humanize(String id) {
        if (id == null || id.isBlank()) return id;
        StringBuilder sb = new StringBuilder();
        for (String part : id.split("_")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return sb.toString();
    }
}
