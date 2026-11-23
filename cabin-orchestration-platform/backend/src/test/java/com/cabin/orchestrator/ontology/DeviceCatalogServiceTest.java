package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.api.DeviceCatalogShadowController;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Catalog S3 (W-161) shadow read. Synthetic devices only; no host, no live inventory. The disagreement report over the
 * live cabin inventory is a post-deploy reading and needs Nate's deploy go; it is not claimed here.
 */
class DeviceCatalogServiceTest {

    private static final ClassLoader NO_RESOURCES = new ClassLoader(null) {};

    private static String imageCopyHash() throws Exception {
        try (InputStream in = DeviceCatalogServiceTest.class.getClassLoader()
                .getResourceAsStream(DeviceCatalogService.CLASSPATH_COPY)) {
            assertNotNull(in, "the generator must write the image copy onto the backend classpath");
            return new ObjectMapper().readTree(in).path("catalog_hash").asText();
        }
    }

    private static DeviceCatalogService imageOnly() {
        return new DeviceCatalogService("/nonexistent/device-catalog.json", DeviceCatalogServiceTest.class.getClassLoader());
    }

    @Test
    void loadsTheImageCopyWhenTheMountIsAbsent() throws Exception {
        DeviceCatalogService service = imageOnly();
        assertEquals("image", service.source());
        assertEquals(imageCopyHash(), service.catalogHash().orElseThrow());
        assertTrue(service.entryCount() > 0);
    }

    @Test
    void prefersTheMountAndFallsBackWhenTheMountIsUnreadable(@TempDir Path dir) throws Exception {
        Path mount = dir.resolve("device-catalog.json");
        Files.writeString(mount, "{\"catalog_hash\":\"mounthash\",\"entries\":[{\"id\":\"LOCK\",\"status\":\"enum\"}]}");
        DeviceCatalogService fromMount = new DeviceCatalogService(mount.toString(), getClass().getClassLoader());
        assertEquals("mount", fromMount.source());
        assertEquals("mounthash", fromMount.catalogHash().orElseThrow());

        Files.writeString(mount, "{ not json");
        DeviceCatalogService fallback = new DeviceCatalogService(mount.toString(), getClass().getClassLoader());
        assertEquals("image", fallback.source());
        assertEquals(imageCopyHash(), fallback.catalogHash().orElseThrow());
    }

    @Test
    void everyUnusableMountFallsBackToAValidImage(@TempDir Path dir) throws Exception {
        String image = imageCopyHash();
        Path missing = dir.resolve("missing.json");
        assertEquals("image", new DeviceCatalogService(missing.toString(), getClass().getClassLoader()).source());
        for (String body : List.of("{ not json", "{}", "null", "[]", "{\"catalog_hash\":\"h\"}",
                "{\"entries\":[]}", "{\"catalog_hash\":\"\",\"entries\":[]}", "{\"catalog_hash\":1,\"entries\":[]}")) {
            Path mount = dir.resolve("device-catalog.json");
            Files.writeString(mount, body);
            DeviceCatalogService service = new DeviceCatalogService(mount.toString(), getClass().getClassLoader());
            assertEquals("image", service.source(), "mount body " + body);
            assertEquals(image, service.catalogHash().orElseThrow(), "mount body " + body);
        }
    }

    @Test
    void unavailableOnlyWhenNoCandidateIsUsable(@TempDir Path dir) throws Exception {
        Path mount = dir.resolve("device-catalog.json");
        Files.writeString(mount, "{}");
        assertEquals("unavailable", new DeviceCatalogService(mount.toString(), NO_RESOURCES).source());
    }

    @Test
    void noCatalogAnywhereIsReportedNotThrown() {
        DeviceCatalogService none = new DeviceCatalogService("/nonexistent/device-catalog.json", NO_RESOURCES);
        assertEquals("unavailable", none.source());
        assertTrue(none.catalogHash().isEmpty());
        DeviceCatalogService.ShadowMatch match = none.shadow(DeviceType.LOCK);
        assertEquals(DeviceCatalogService.Disagreement.CATALOG_UNAVAILABLE, match.disagreement());
        assertNull(match.ontologyTypeId());
    }

    @Test
    void everyDeviceTypeConstantMatchesAnEnumEntryOfTheShippedCatalog() {
        DeviceCatalogService service = imageOnly();
        for (DeviceType type : DeviceType.values()) {
            DeviceCatalogService.ShadowMatch match = service.shadow(type);
            assertNull(match.disagreement(), type + " has no enum entry in the shipped catalog");
            assertEquals(type.name(), match.ontologyTypeId());
            assertEquals(DeviceCatalogService.MATCHED_BY_ASSIGNED_TYPE, match.matchedBy());
            assertNotNull(match.ontologyContentHash(), type + " entry carries no ontology stamp");
        }
    }

    @Test
    void missingAndOntologyOnlyEntriesAreDisagreements(@TempDir Path dir) throws Exception {
        Path mount = dir.resolve("device-catalog.json");
        Files.writeString(mount, "{\"catalog_hash\":\"h\",\"entries\":[{\"id\":\"LOCK\",\"status\":\"ontology_only\"}]}");
        DeviceCatalogService service = new DeviceCatalogService(mount.toString(), NO_RESOURCES);
        assertEquals(DeviceCatalogService.Disagreement.ONTOLOGY_ONLY_ENTRY, service.shadow(DeviceType.LOCK).disagreement());
        assertEquals(DeviceCatalogService.Disagreement.NO_CATALOG_ENTRY, service.shadow(DeviceType.CAMERA).disagreement());
    }

    private static DeviceStatus status(String id, DeviceType type) {
        return new DeviceStatus(id, type, "synthetic " + id, "ONLINE", Instant.parse("2026-11-23T00:00:00Z"),
            Map.of("temperature", 20.5), "cabin");
    }

    @Test
    void deviceReadsCarryTheShadowStampAndNothingElseChanges() {
        DeviceRegistry plain = new DeviceRegistry(List.of());
        DeviceRegistry shadowed = new DeviceRegistry(List.of());
        DeviceCatalogService service = imageOnly();
        shadowed.setCatalogService(service);
        plain.register(status("z2m-lock", DeviceType.LOCK));
        shadowed.register(status("z2m-lock", DeviceType.LOCK));

        DeviceStatus before = plain.get("z2m-lock");
        DeviceStatus after = shadowed.get("z2m-lock");
        assertEquals(before.type(), after.type(), "the shadow read must not change the assigned type");
        assertFalse(before.attributes().containsKey("ontologyTypeId"));

        Map<String, Object> attrs = after.attributes();
        assertEquals("LOCK", attrs.get("ontologyTypeId"));
        assertEquals("assigned_type", attrs.get("matchedBy"));
        assertEquals("image", attrs.get("catalogSource"));
        assertEquals(service.catalogHash().orElseThrow(), attrs.get("catalogHash"));
        assertNotNull(attrs.get("ontologyContentHash"));
        assertFalse(attrs.containsKey("catalogDisagreement"));
        Map<String, Object> rest = new java.util.LinkedHashMap<>(attrs);
        List.of("ontologyTypeId", "matchedBy", "catalogSource", "catalogHash", "ontologyContentHash").forEach(rest::remove);
        assertEquals(before.attributes(), rest, "only the five shadow keys are added");
    }

    @Test
    void staleShadowKeysNeverSurviveAFreshResult(@TempDir Path dir) throws Exception {
        // stale failure, current success
        DeviceRegistry ok = new DeviceRegistry(List.of());
        ok.setCatalogService(imageOnly());
        ok.register(new DeviceStatus("z2m-lock", DeviceType.LOCK, "lock", "ONLINE", Instant.parse("2026-11-23T00:00:00Z"),
            Map.of("catalogDisagreement", "CATALOG_UNAVAILABLE", "temperature", 1), "cabin"));
        Map<String, Object> success = ok.get("z2m-lock").attributes();
        assertFalse(success.containsKey("catalogDisagreement"), "stale disagreement survived a successful match");
        assertEquals("LOCK", success.get("ontologyTypeId"));
        assertEquals(1, success.get("temperature"), "attributes the shadow does not own are untouched");

        // stale success, current failure
        DeviceRegistry failing = new DeviceRegistry(List.of());
        failing.setCatalogService(new DeviceCatalogService("/nonexistent/device-catalog.json", NO_RESOURCES));
        failing.register(new DeviceStatus("z2m-lock", DeviceType.LOCK, "lock", "ONLINE", Instant.parse("2026-11-23T00:00:00Z"),
            Map.of("ontologyTypeId", "LOCK", "ontologyContentHash", "old", "catalogHash", "old", "matchedBy", "assigned_type",
                "temperature", 1), "cabin"));
        Map<String, Object> failure = failing.get("z2m-lock").attributes();
        assertEquals("CATALOG_UNAVAILABLE", failure.get("catalogDisagreement"));
        assertEquals("unavailable", failure.get("catalogSource"));
        for (String key : List.of("ontologyTypeId", "ontologyContentHash", "catalogHash", "matchedBy")) {
            assertFalse(failure.containsKey(key), "stale " + key + " survived a failed match");
        }
        assertEquals(1, failure.get("temperature"));
    }

    @Test
    void reportCountsMatchesAndDisagreements(@TempDir Path dir) throws Exception {
        Path mount = dir.resolve("device-catalog.json");
        Files.writeString(mount, "{\"catalog_hash\":\"h\",\"entries\":[{\"id\":\"LOCK\",\"status\":\"enum\"}]}");
        DeviceCatalogService service = new DeviceCatalogService(mount.toString(), NO_RESOURCES);
        DeviceRegistry registry = new DeviceRegistry(List.of());
        registry.register(status("lock-1", DeviceType.LOCK));
        registry.register(status("cam-1", DeviceType.CAMERA));
        registry.register(status("cam-2", DeviceType.CAMERA));

        Map<String, Object> report = new DeviceCatalogShadowController(registry, service).report();
        assertEquals("mount", report.get("catalogSource"));
        // The registry also holds its built-in devices, so check the three synthetic rows and the totals.
        int devices = registry.all().size();
        assertEquals(devices, report.get("devices"));
        @SuppressWarnings("unchecked")
        Map<String, List<String>> disagreements = (Map<String, List<String>>) report.get("disagreements");
        int disagreeing = disagreements.values().stream().mapToInt(List::size).sum();
        assertEquals(devices - disagreeing, report.get("matched"));
        assertTrue(disagreements.get("NO_CATALOG_ENTRY").containsAll(List.of("cam-1", "cam-2")));
        assertFalse(disagreements.values().stream().anyMatch(ids -> ids.contains("lock-1")));
        @SuppressWarnings("unchecked")
        Map<String, Integer> byEntry = (Map<String, Integer>) report.get("matchedByEntry");
        assertEquals(devices - disagreeing, byEntry.getOrDefault("LOCK", 0), "only LOCK is in this catalog");
    }
}
