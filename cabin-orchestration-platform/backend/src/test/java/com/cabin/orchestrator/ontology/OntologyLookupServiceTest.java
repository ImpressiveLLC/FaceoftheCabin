package com.cabin.orchestrator.ontology;

import com.cabin.orchestrator.workflow.model.ActionVocabularyEntry;
import com.cabin.orchestrator.workflow.model.TriggerVocabularyEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * listCandidateTriggers()/listCandidateActions() -- added 2026-08-21 as the
 * candidate half of RulesController's new vocabulary endpoints (see
 * JdbcWorkflowVocabularyStore's own doc for the supported half). Uses a
 * small fixture file rather than the real docs/ontology.yaml so this test
 * doesn't depend on that file's real repo-relative path or drift when its
 * content changes.
 */
class OntologyLookupServiceTest {

    @TempDir
    Path tempDir;

    private OntologyLookupService service;

    @BeforeEach
    void setUp() throws IOException {
        Path fixture = tempDir.resolve("ontology.yaml");
        Files.writeString(fixture, """
            elements:
              - id: trigger_water_leak_detected
                ui_display_name: "Water leak detected"
                lifecycle_status: used
                trigger_source: { event_type_pattern: TELEMETRY, payload_field: water_leak }
              - id: trigger_rf_tripwire_crossed
                ui_display_name: "RF tripwire crossed"
                lifecycle_status: candidate
                applies_to_device_type: [RF_TRIPWIRE]
                applies_to_capability: (undetermined -- see notes)
                trigger_source: { event_type_pattern: TRIPWIRE_CROSSED }
              - id: action_entry_light_on
                ui_display_name: "Turn on the entry light"
                lifecycle_status: candidate
                action_kind: command
                requires_capability: COMMAND
              - id: action_main_water_valve_off
                ui_display_name: "Shut off the main water valve"
                lifecycle_status: used
                action_kind: command
              - id: cabin_camera_event
                ui_display_name: "Camera Event"
                lifecycle_status: used
            """);
        service = new OntologyLookupService();
        ReflectionTestUtils.setField(service, "ontologyPath", fixture.toString());
    }

    @Test
    void listCandidateTriggersReturnsOnlyCandidateEntriesWithATriggerSource() {
        List<TriggerVocabularyEntry> candidates = service.listCandidateTriggers(Set.of());

        assertEquals(1, candidates.size());
        TriggerVocabularyEntry t = candidates.get(0);
        assertEquals("trigger_rf_tripwire_crossed", t.id());
        assertEquals("RF tripwire crossed", t.label());
        assertEquals("RF_TRIPWIRE", t.appliesToDeviceType());
        assertFalse(t.supported(), "a candidate must never be marked supported");
    }

    @Test
    void listCandidateTriggersExcludesAnIdAlreadyCoveredBySeededSupportedRows() {
        // trigger_water_leak_detected is `used`, not `candidate`, so it
        // would already be excluded by the lifecycle_status filter alone --
        // this asserts the excludeIds dedup path itself, in case an entry
        // is ever mislabeled candidate despite already being wired (a real,
        // if minor, drift this project has hit before -- see
        // trigger_water_leak_cleared's own notes).
        List<TriggerVocabularyEntry> candidates = service.listCandidateTriggers(Set.of("trigger_rf_tripwire_crossed"));

        assertTrue(candidates.isEmpty());
    }

    @Test
    void listCandidateActionsReturnsOnlyCandidateEntriesWithAnActionKind() {
        List<ActionVocabularyEntry> candidates = service.listCandidateActions(Set.of());

        assertEquals(1, candidates.size());
        ActionVocabularyEntry a = candidates.get(0);
        assertEquals("action_entry_light_on", a.id());
        assertEquals("command", a.actionKind());
        assertTrue(a.needsTarget());
        assertFalse(a.privileged(), "no candidate is ever privileged -- only the seeded reopen action is");
        assertFalse(a.supported());
    }

    @Test
    void nonCandidateAndNonAutomationEntitiesAreNeverIncluded() {
        // action_main_water_valve_off is `used` (already seeded elsewhere);
        // cabin_camera_event has neither trigger_source nor action_kind at
        // all -- both must be silently skipped, not error.
        assertTrue(service.listCandidateTriggers(Set.of()).stream().noneMatch(t -> t.id().equals("cabin_camera_event")));
        assertTrue(service.listCandidateActions(Set.of()).stream().noneMatch(a -> a.id().equals("action_main_water_valve_off")));
    }

    @Test
    void degradesToAnEmptyListRatherThanThrowingWhenNoOntologyFileIsMounted() {
        OntologyLookupService unmounted = new OntologyLookupService();
        ReflectionTestUtils.setField(unmounted, "ontologyPath", tempDir.resolve("does-not-exist.yaml").toString());

        assertEquals(List.of(), unmounted.listCandidateTriggers(Set.of()));
        assertEquals(List.of(), unmounted.listCandidateActions(Set.of()));
    }
}
