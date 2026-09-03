package com.cabin.orchestrator.platformimport;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PlatformImportTranslationServiceTest {

    private final PlatformImportTranslationService service = new PlatformImportTranslationService();

    @Test
    void proposesASlugEntityIdFromTheDeviceName() {
        RawImportRecord raw = new RawImportRecord("smartthings", "abc-123", "Kitchen Temp Sensor", "loc-1",
            List.of("temperature"), Map.of());

        ImportProposal proposal = service.propose(raw);

        assertEquals("smartthings-kitchen_temp_sensor", proposal.entityIdCandidate());
    }

    @Test
    void exactlyOneMeasurementTypeCandidateIsHighConfidence() {
        RawImportRecord raw = new RawImportRecord("smartthings", "1", "Temp", "loc", List.of("temperature"), Map.of());

        assertEquals("HIGH", service.propose(raw).confidence());
    }

    @Test
    void multipleMeasurementTypeCandidatesAreMediumConfidence() {
        RawImportRecord raw = new RawImportRecord("ring", "1", "Doorbell", "addr", List.of("motion", "contact"), Map.of());

        assertEquals("MEDIUM", service.propose(raw).confidence());
    }

    @Test
    void noMeasurementTypeCandidatesIsLowConfidenceNeverFabricated() {
        RawImportRecord raw = new RawImportRecord("ring", "1", "Mystery Device", "addr", List.of(), Map.of());

        assertEquals("LOW", service.propose(raw).confidence());
    }

    @Test
    void aBlankNameStillProducesAUsableEntityIdCandidate() {
        RawImportRecord raw = new RawImportRecord("ring", "1", "   ", "addr", List.of(), Map.of());

        assertEquals("ring-unnamed", service.propose(raw).entityIdCandidate());
    }

    @Test
    void measurementTypeCandidatesFlowThroughUnchanged() {
        RawImportRecord raw = new RawImportRecord("smartthings", "1", "Sensor", "loc", List.of("temperature", "humidity"), Map.of());

        assertEquals(List.of("temperature", "humidity"), service.propose(raw).measurementTypeCandidates());
    }
}
