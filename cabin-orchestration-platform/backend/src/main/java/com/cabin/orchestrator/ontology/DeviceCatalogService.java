package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catalog S3 (W-161, roadmap #449): a shadow read of the generated device catalog. No behavior change.
 *
 * <p>Loads docs/ontology/device-catalog.json once at startup: the docs mount first ({@code cabin.catalog.path}), then
 * the byte-identical copy the generator writes onto the classpath (the image copy), so a host without the mount still
 * has a catalog. If neither is readable the source is {@code unavailable} and every device read says so; nothing
 * fails, because S3 only observes (fail-closed enrollment is S4).
 *
 * <p>{@link #shadow(DeviceType)} answers which catalog entry a device's assigned type corresponds to. The match is by
 * the type the existing {@code inferType} sites already assigned ({@code matchedBy = "assigned_type"}): the catalog's
 * match rules are reviewed fixtures that the parity test runs against the real methods, not executable predicates, so
 * this slice cannot classify a device independently of {@code inferType}. It records the disagreements it can see
 * honestly (no catalog entry for the type, an ontology-only entry, no catalog loaded). Independent rule evaluation is a
 * separate, named follow-up, not claimed here.
 */
@Service
public class DeviceCatalogService {

    private static final Logger log = LoggerFactory.getLogger(DeviceCatalogService.class);
    static final String CLASSPATH_COPY = "ontology/device-catalog.json";
    public static final String MATCHED_BY_ASSIGNED_TYPE = "assigned_type";

    public enum Disagreement { CATALOG_UNAVAILABLE, NO_CATALOG_ENTRY, ONTOLOGY_ONLY_ENTRY }

    /** What one device read carries. {@code ontologyTypeId} is null whenever {@code disagreement} is set. */
    public record ShadowMatch(String ontologyTypeId, String matchedBy, String ontologyContentHash,
                              Disagreement disagreement) {}

    private final String source;
    private final String catalogHash;
    private final Map<String, JsonNode> entries;
    private final Set<String> logged = ConcurrentHashMap.newKeySet();

    public DeviceCatalogService(@Value("${cabin.catalog.path:/app/docs/ontology/device-catalog.json}") String mountPath) {
        this(mountPath, DeviceCatalogService.class.getClassLoader());
    }

    DeviceCatalogService(String mountPath, ClassLoader classLoader) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode catalog = null;
        String from = "unavailable";
        Path mount = mountPath == null || mountPath.isBlank() ? null : Path.of(mountPath);
        if (mount != null && Files.isReadable(mount)) {
            try (InputStream in = Files.newInputStream(mount)) {
                catalog = mapper.readTree(in);
                from = "mount";
            } catch (IOException | RuntimeException e) {
                log.warn("Device catalog at {} is unreadable ({}); trying the image copy", mount, e.toString());
            }
        }
        if (catalog == null) {
            try (InputStream in = classLoader.getResourceAsStream(CLASSPATH_COPY)) {
                if (in != null) {
                    catalog = mapper.readTree(in);
                    from = "image";
                }
            } catch (IOException | RuntimeException e) {
                log.warn("Device catalog image copy is unreadable ({})", e.toString());
            }
        }
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        String hash = null;
        if (catalog != null && catalog.path("entries").isArray() && catalog.path("catalog_hash").isTextual()) {
            catalog.path("entries").forEach(entry -> byId.put(entry.path("id").asText(), entry));
            hash = catalog.path("catalog_hash").asText();
        } else {
            from = "unavailable";
        }
        this.source = from;
        this.catalogHash = hash;
        this.entries = Collections.unmodifiableMap(byId);
        log.info("Device catalog shadow read: source={}, entries={}, hash={}", source, entries.size(),
            hash == null ? "none" : hash.substring(0, Math.min(12, hash.length())));
    }

    /** {@code mount}, {@code image} or {@code unavailable}. */
    public String source() { return source; }

    public Optional<String> catalogHash() { return Optional.ofNullable(catalogHash); }

    public int entryCount() { return entries.size(); }

    public ShadowMatch shadow(DeviceType type) {
        if (catalogHash == null) return new ShadowMatch(null, null, null, Disagreement.CATALOG_UNAVAILABLE);
        JsonNode entry = type == null ? null : entries.get(type.name());
        if (entry == null) return new ShadowMatch(null, MATCHED_BY_ASSIGNED_TYPE, null, Disagreement.NO_CATALOG_ENTRY);
        if (!"enum".equals(entry.path("status").asText())) {
            return new ShadowMatch(null, MATCHED_BY_ASSIGNED_TYPE, null, Disagreement.ONTOLOGY_ONLY_ENTRY);
        }
        String ontologyHash = entry.path("stamp").path("ontology_content_hash").asText(null);
        return new ShadowMatch(entry.path("id").asText(), MATCHED_BY_ASSIGNED_TYPE, ontologyHash, null);
    }

    /** Logs a disagreement once per device and reason, so a read-heavy page cannot flood the log. */
    public void logDisagreement(String deviceId, DeviceType type, Disagreement disagreement) {
        if (disagreement == null || !logged.add(deviceId + "|" + disagreement)) return;
        log.warn("Device catalog shadow disagreement: device={} assignedType={} reason={}", deviceId, type, disagreement);
    }
}
