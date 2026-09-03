package com.cabin.orchestrator.integrations.smartthings;

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
class SmartThingsImportProviderTest {

    private final SmartThingsImportProvider provider = new SmartThingsImportProvider(new NeverCalledCredentialStore());

    @Test
    void parsesATemperatureSensor() {
        String json = """
            {"items": [{
              "deviceId": "temp-1", "name": "temp sensor", "label": "Kitchen Temp",
              "locationId": "loc-1",
              "components": [{"id": "main", "capabilities": [{"id": "temperatureMeasurement"}, {"id": "battery"}]}]
            }]}""";

        List<RawImportRecord> records = provider.parseDevices(json);

        assertEquals(1, records.size());
        RawImportRecord record = records.get(0);
        assertEquals("smartthings", record.platform());
        assertEquals("temp-1", record.originalId());
        assertEquals("Kitchen Temp", record.originalName(), "label must win over name when present");
        assertEquals("loc-1", record.originalLocation());
        assertEquals(List.of("temperature", "battery"), record.measurementTypeCandidates());
    }

    @Test
    void parsesAMotionSensor() {
        String json = """
            {"items": [{
              "deviceId": "motion-1", "name": "motion sensor", "label": "Hallway Motion",
              "locationId": "loc-1",
              "components": [{"id": "main", "capabilities": [{"id": "motionSensor"}]}]
            }]}""";

        RawImportRecord record = provider.parseDevices(json).get(0);

        assertEquals(List.of("motion"), record.measurementTypeCandidates());
    }

    @Test
    void parsesAContactSensorWithNoLabelFallingBackToName() {
        String json = """
            {"items": [{
              "deviceId": "contact-1", "name": "Front Door Contact",
              "locationId": "loc-2",
              "components": [{"id": "main", "capabilities": [{"id": "contactSensor"}, {"id": "battery"}]}]
            }]}""";

        RawImportRecord record = provider.parseDevices(json).get(0);

        assertEquals("Front Door Contact", record.originalName(), "no label -- must fall back to name");
        assertEquals(List.of("contact", "battery"), record.measurementTypeCandidates());
    }

    @Test
    void anUnrecognizedCapabilityIsIgnoredRatherThanGuessed() {
        String json = """
            {"items": [{
              "deviceId": "weird-1", "name": "Weird Device", "locationId": "loc-1",
              "components": [{"id": "main", "capabilities": [{"id": "someFutureCapability"}]}]
            }]}""";

        RawImportRecord record = provider.parseDevices(json).get(0);

        assertEquals(List.of(), record.measurementTypeCandidates());
    }

    @Test
    void endToEndThroughTranslationProducesAnEntityIdAndCandidatesForEachDeviceType() {
        PlatformImportTranslationService translation = new PlatformImportTranslationService();
        String json = """
            {"items": [
              {"deviceId": "t1", "label": "Kitchen Temp", "locationId": "loc-1",
               "components": [{"id": "main", "capabilities": [{"id": "temperatureMeasurement"}]}]},
              {"deviceId": "m1", "label": "Hallway Motion", "locationId": "loc-1",
               "components": [{"id": "main", "capabilities": [{"id": "motionSensor"}]}]},
              {"deviceId": "c1", "label": "Front Door Contact", "locationId": "loc-2",
               "components": [{"id": "main", "capabilities": [{"id": "contactSensor"}]}]}
            ]}""";

        List<RawImportRecord> records = provider.parseDevices(json);
        assertEquals(3, records.size());
        records.forEach(record -> {
            var proposal = translation.propose(record);
            assertNotNull(proposal.entityIdCandidate());
            assertTrue(proposal.entityIdCandidate().startsWith("smartthings-"));
            assertFalse(proposal.measurementTypeCandidates().isEmpty());
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
