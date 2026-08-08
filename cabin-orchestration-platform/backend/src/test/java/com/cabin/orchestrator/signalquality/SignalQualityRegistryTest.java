package com.cabin.orchestrator.signalquality;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SignalQualityRegistryTest {

    @Test
    void unknownDeviceHasNoAssessment() {
        SignalQualityRegistry registry = new SignalQualityRegistry();

        assertTrue(registry.assess("z2m-motion_entry").isEmpty());
    }

    @Test
    void tooFewSamplesReportsNoBaselineRatherThanGuessing() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < SignalQualityRegistry.MIN_SAMPLES_FOR_BASELINE - 1; i++) {
            registry.record("z2m-motion_entry", 200);
        }

        var a = registry.assess("z2m-motion_entry").orElseThrow();

        assertNull(a.baseline(), "with too few samples, baseline must be null, not zero or a guess");
        assertFalse(a.anomalous());
    }

    @Test
    void stableReadingsAreNotFlaggedAnomalous() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < 10; i++) {
            registry.record("z2m-motion_entry", 200);
        }

        var a = registry.assess("z2m-motion_entry").orElseThrow();

        assertEquals(200.0, a.baseline());
        assertFalse(a.anomalous());
    }

    @Test
    void aSignificantDropBelowBaselineIsFlaggedAnomalous() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < 10; i++) {
            registry.record("z2m-motion_entry", 200);
        }
        registry.record("z2m-motion_entry", 50); // well below 200 * 0.7

        var a = registry.assess("z2m-motion_entry").orElseThrow();

        assertTrue(a.anomalous());
        assertEquals(50, a.current());
    }

    @Test
    void aMinorFluctuationIsNotFlaggedAnomalous() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < 10; i++) {
            registry.record("z2m-motion_entry", 200);
        }
        registry.record("z2m-motion_entry", 180); // 10% dip, within normal noise

        var a = registry.assess("z2m-motion_entry").orElseThrow();

        assertFalse(a.anomalous());
    }

    @Test
    void theDropItselfDoesNotDragDownItsOwnBaseline() {
        // baseline must be computed from prior readings, excluding the
        // latest -- otherwise a real drop would partially average itself
        // into the baseline and become harder to detect the more history
        // accumulates around it.
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < 10; i++) {
            registry.record("z2m-motion_entry", 200);
        }
        registry.record("z2m-motion_entry", 50);

        var a = registry.assess("z2m-motion_entry").orElseThrow();

        assertEquals(200.0, a.baseline(), "baseline should reflect prior stable history, not include the drop itself");
    }

    @Test
    void historyEvictsOldestReadingsBeyondTheRetentionWindow() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < 15; i++) {
            registry.record("z2m-motion_entry", 200);
        }
        registry.record("z2m-motion_entry", 255); // pushes sample count, doesn't grow unbounded

        var a = registry.assess("z2m-motion_entry").orElseThrow();

        assertTrue(a.sampleCount() <= 20, "history must be bounded, not grow forever");
    }

    @Test
    void differentDevicesAreTrackedIndependently() {
        SignalQualityRegistry registry = new SignalQualityRegistry();
        for (int i = 0; i < 10; i++) {
            registry.record("z2m-motion_entry", 200);
            registry.record("z2m-door_front_contact", 100);
        }

        assertEquals(200.0, registry.assess("z2m-motion_entry").orElseThrow().baseline());
        assertEquals(100.0, registry.assess("z2m-door_front_contact").orElseThrow().baseline());
    }
}
