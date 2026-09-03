package com.cabin.orchestrator.integrations.ring;

import com.cabin.orchestrator.platformimport.PlatformImportTranslationService;
import com.cabin.orchestrator.platformimport.RawImportRecord;
import com.cabin.orchestrator.security.OAuthCredential;
import com.cabin.orchestrator.security.OAuthCredentialStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WSJF #9 success criterion 5: fixture-driven, no live API call. parseDevices()
 * is the pure function under test -- listDevices() (the live HTTP path) is
 * exercised nowhere here.
 */
class RingImportProviderTest {

    private final RingImportProvider provider = new RingImportProvider(new NeverCalledCredentialStore());

    @Test
    void parsesADoorbell() {
        String json = """
            {"doorbells": [{"id": 123, "description": {"name": "Front Door"}, "kind": "doorbell_v3", "address": "123 Main St"}],
             "authorized_doorbells": [], "chimes": [], "other": []}""";

        List<RawImportRecord> records = provider.parseDevices(json);

        assertEquals(1, records.size());
        RawImportRecord record = records.get(0);
        assertEquals("ring", record.platform());
        assertEquals("123", record.originalId());
        assertEquals("Front Door", record.originalName());
        assertEquals("123 Main St", record.originalLocation());
        assertEquals(List.of("motion", "contact"), record.measurementTypeCandidates());
    }

    @Test
    void parsesACamera() {
        String json = """
            {"doorbells": [], "authorized_doorbells": [], "chimes": [],
             "other": [{"id": 456, "description": {"name": "Driveway Cam"}, "kind": "cam_v2", "address": "123 Main St"}]}""";

        RawImportRecord record = provider.parseDevices(json).get(0);

        assertEquals(List.of("motion"), record.measurementTypeCandidates());
    }

    @Test
    void parsesAnAlarmSensorSurfacingEveryPlausibleCandidateSinceKindIsOnlyAHint() {
        String json = """
            {"doorbells": [], "authorized_doorbells": [], "chimes": [],
             "other": [{"id": 789, "description": {"name": "Basement Sensor"}, "kind": "alarm_v1", "address": "123 Main St"}]}""";

        RawImportRecord record = provider.parseDevices(json).get(0);

        assertEquals(List.of("motion", "co", "leak"), record.measurementTypeCandidates());
    }

    @Test
    void anUnrecognizedKindYieldsNoCandidatesRatherThanAGuess() {
        String json = """
            {"doorbells": [], "authorized_doorbells": [], "chimes": [],
             "other": [{"id": 999, "description": {"name": "Mystery"}, "kind": "future_widget", "address": "addr"}]}""";

        RawImportRecord record = provider.parseDevices(json).get(0);

        assertEquals(List.of(), record.measurementTypeCandidates());
    }

    @Test
    void endToEndThroughTranslationProducesAnEntityIdAndCandidatesForEachDeviceType() {
        PlatformImportTranslationService translation = new PlatformImportTranslationService();
        String json = """
            {"doorbells": [{"id": 1, "description": {"name": "Front Door"}, "kind": "doorbell_v3", "address": "addr"}],
             "authorized_doorbells": [],
             "chimes": [{"id": 2, "description": {"name": "Living Room Chime"}, "kind": "chime_v2", "address": "addr"}],
             "other": [{"id": 3, "description": {"name": "Driveway Cam"}, "kind": "cam_v2", "address": "addr"}]}""";

        List<RawImportRecord> records = provider.parseDevices(json);
        assertEquals(3, records.size());
        records.forEach(record -> {
            var proposal = translation.propose(record);
            assertNotNull(proposal.entityIdCandidate());
            assertTrue(proposal.entityIdCandidate().startsWith("ring-"));
        });
    }

    private static final class NeverCalledCredentialStore implements OAuthCredentialStore {
        @Override public void store(String vaultEntryName, OAuthCredential credential) {
            throw new AssertionError("not expected to be called in this test");
        }
        @Override public Optional<OAuthCredential> retrieve(String vaultEntryName) {
            throw new AssertionError("not expected to be called in this test");
        }
    }
}
