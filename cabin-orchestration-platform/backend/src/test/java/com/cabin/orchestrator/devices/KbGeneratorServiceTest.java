package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KbGeneratorServiceTest {

    private DeviceRegistry registry;
    private FakeDeviceRepository deviceRepository;
    private FakeReportingRelationshipRepository reportingRepository;
    private FakeKnowledgeNodeRepository knowledgeNodeRepository;
    private KbGeneratorService generator;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        deviceRepository = new FakeDeviceRepository();
        reportingRepository = new FakeReportingRelationshipRepository();
        knowledgeNodeRepository = new FakeKnowledgeNodeRepository();
        generator = new KbGeneratorService(registry, deviceRepository, reportingRepository, knowledgeNodeRepository);
    }

    private void confirm(String deviceId, String name, DeviceType type, String location) {
        registry.registerCandidate(new DeviceDescriptor(
            deviceId, name, type, Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic/" + deviceId, true, location),
            Map.of());
        registry.applyLifecycleAction(deviceId, DeviceLifecycleAction.ACCEPT);
    }

    @Test
    void regenerateForBuildsADescriptionChunkFromMetadataAndType() {
        confirm("z2m-temp_kitchen", "Kitchen temp", DeviceType.TEMPERATURE_SENSOR, "cabin");
        deviceRepository.put("z2m-temp_kitchen", new DeviceMetadata(
            "SONOFF", "SNZB-02WD", "mech_room", Instant.now(), "system", Instant.now(), "system", Instant.now(), 1));

        generator.regenerateFor("z2m-temp_kitchen");

        KnowledgeNode description = knowledgeNodeRepository.find("z2m-temp_kitchen", KnowledgeChunkType.DESCRIPTION).orElseThrow();
        assertTrue(description.content().contains("SONOFF"));
        assertTrue(description.content().contains("SNZB-02WD"));
        assertTrue(description.content().contains("mech_room"), "area must win over the coarser cabin/home location when present");
        assertEquals(KnowledgeSource.AUTO_GENERATED, description.source());
    }

    @Test
    void regenerateForFallsBackToLocationWhenNoAreaIsRecorded() {
        confirm("z2m-temp_outside", "Outside temp", DeviceType.TEMPERATURE_SENSOR, "cabin");
        // No deviceRepository.put() -- no metadata at all yet.

        generator.regenerateFor("z2m-temp_outside");

        KnowledgeNode description = knowledgeNodeRepository.find("z2m-temp_outside", KnowledgeChunkType.DESCRIPTION).orElseThrow();
        assertTrue(description.content().contains("cabin"));
    }

    @Test
    void regenerateForWritesARelationshipChunkOnlyWhenReportingDataExists() {
        confirm("z2m-humid_mech", "Mech room humidity", DeviceType.TEMPERATURE_SENSOR, "cabin");
        reportingRepository.add("z2m-humid_mech", "humidity");
        reportingRepository.add("z2m-humid_mech", "temperature");

        int written = generator.regenerateFor("z2m-humid_mech");

        assertEquals(2, written, "one description chunk plus one relationship chunk");
        KnowledgeNode relationship = knowledgeNodeRepository.find("z2m-humid_mech", KnowledgeChunkType.RELATIONSHIP).orElseThrow();
        assertTrue(relationship.content().contains("humidity"));
        assertTrue(relationship.content().contains("temperature"));
    }

    @Test
    void regenerateForOmitsTheRelationshipChunkWhenNothingIsConfirmedReported() {
        confirm("z2m-never_reported", "Silent sensor", DeviceType.TEMPERATURE_SENSOR, "cabin");

        int written = generator.regenerateFor("z2m-never_reported");

        assertEquals(1, written, "just the description chunk -- an empty relationship chunk would be noise, not a fact");
        assertTrue(knowledgeNodeRepository.find("z2m-never_reported", KnowledgeChunkType.RELATIONSHIP).isEmpty());
    }

    @Test
    void regenerateAllSkipsStillUndecidedCandidates() {
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-candidate", "Undecided", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "topic/candidate", false, "cabin"), Map.of());
        confirm("z2m-confirmed", "Confirmed device", DeviceType.TEMPERATURE_SENSOR, "cabin");

        generator.regenerateAll();

        assertTrue(knowledgeNodeRepository.find("z2m-confirmed", KnowledgeChunkType.DESCRIPTION).isPresent());
        assertTrue(knowledgeNodeRepository.find("z2m-candidate", KnowledgeChunkType.DESCRIPTION).isEmpty(),
            "an undecided candidate isn't a confirmed fact yet -- KB Generator shouldn't describe it");
    }

    @Test
    void regenerateForNeverOverwritesAManuallyCuratedDescription() {
        confirm("z2m-curated_device", "Curated device", DeviceType.TEMPERATURE_SENSOR, "cabin");
        knowledgeNodeRepository.upsert(new KnowledgeNode("z2m-curated_device", KnowledgeChunkType.DESCRIPTION,
            "A person's own hand-written description.", KnowledgeSource.MANUALLY_CURATED, Instant.now()));

        generator.regenerateFor("z2m-curated_device");

        KnowledgeNode found = knowledgeNodeRepository.find("z2m-curated_device", KnowledgeChunkType.DESCRIPTION).orElseThrow();
        assertEquals("A person's own hand-written description.", found.content());
        assertEquals(KnowledgeSource.MANUALLY_CURATED, found.source());
    }

    @Test
    void upsertAutoGeneratedRejectsCredentialPointer() {
        // WSJF #8, D5's hard rule: CREDENTIAL_POINTER must only ever be
        // MANUALLY_CURATED (see CredentialPointerSeeder). A real guard, not
        // just an absence of a call site among the two the generator
        // actually uses today.
        assertThrows(IllegalArgumentException.class, () ->
            generator.upsertAutoGenerated("z2m-whatever", KnowledgeChunkType.CREDENTIAL_POINTER, "vault_something", Instant.now()));
        assertEquals(0, knowledgeNodeRepository.countFor("z2m-whatever"), "the rejected write must not have happened");
    }

    @Test
    void regenerateForIsIdempotentNotAccumulating() {
        confirm("z2m-repeat", "Repeat device", DeviceType.TEMPERATURE_SENSOR, "cabin");

        generator.regenerateFor("z2m-repeat");
        generator.regenerateFor("z2m-repeat");

        assertEquals(1, knowledgeNodeRepository.countFor("z2m-repeat"),
            "regenerating twice must update the same row, not create a duplicate");
    }

    private static final class FakeDeviceRepository implements DeviceRepository {
        private final Map<String, DeviceMetadata> data = new LinkedHashMap<>();
        void put(String deviceId, DeviceMetadata metadata) { data.put(deviceId, metadata); }
        @Override public void upsert(String deviceId, DeviceMetadata metadata) { data.put(deviceId, metadata); }
        @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.ofNullable(data.get(deviceId)); }
        @Override public Map<String, DeviceMetadata> loadAll() { return Map.copyOf(data); }
    }

    private static final class FakeReportingRelationshipRepository implements DeviceReportingRelationshipRepository {
        private final Map<String, List<DeviceReportingRelationship>> data = new LinkedHashMap<>();
        void add(String deviceId, String field) {
            data.computeIfAbsent(deviceId, k -> new ArrayList<>()).add(new DeviceReportingRelationship(
                deviceId, field, field, ConfirmationSource.VENDOR_SPEC, Instant.now()));
        }
        @Override public void upsert(DeviceReportingRelationship relationship) {
            data.computeIfAbsent(relationship.deviceId(), k -> new ArrayList<>()).add(relationship);
        }
        @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) {
            return data.getOrDefault(deviceId, List.of());
        }
        @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.copyOf(data); }
    }

    private static final class FakeKnowledgeNodeRepository implements KnowledgeNodeRepository {
        private final Map<String, KnowledgeNode> data = new LinkedHashMap<>();
        private static String key(String entityRef, KnowledgeChunkType chunkType) { return entityRef + "|" + chunkType; }
        Optional<KnowledgeNode> find(String entityRef, KnowledgeChunkType chunkType) {
            return Optional.ofNullable(data.get(key(entityRef, chunkType)));
        }
        long countFor(String entityRef) {
            return data.keySet().stream().filter(k -> k.startsWith(entityRef + "|")).count();
        }
        @Override public void upsert(KnowledgeNode node) { data.put(key(node.entityRef(), node.chunkType()), node); }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) {
            return data.values().stream().filter(n -> n.entityRef().equals(entityRef)).toList();
        }
        @Override public List<KnowledgeNode> loadAll() { return List.copyOf(data.values()); }
    }
}
