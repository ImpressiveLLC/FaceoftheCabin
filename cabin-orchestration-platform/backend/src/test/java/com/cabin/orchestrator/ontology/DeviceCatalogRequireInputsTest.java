package com.cabin.orchestrator.ontology;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cowork 7075465576 item (c): in the ontology-catalog-gate workflow (REQUIRE_ONTOLOGY_DOCS=true) a missing docs or source
 * input must FAIL DeviceCatalogParityTest; anywhere else it still skips.
 */
class DeviceCatalogRequireInputsTest {

    private final Supplier<String> original = DeviceCatalogParityTest.envReader;

    @AfterEach
    void restore() {
        DeviceCatalogParityTest.envReader = original;
    }

    @Test
    void missingInputFailsWhenTheGateRequiresInputs() {
        DeviceCatalogParityTest.envReader = () -> "true";
        AssertionFailedError error = assertThrows(AssertionFailedError.class,
                () -> DeviceCatalogParityTest.requirePresent(false, "docs/ontology/device-catalog.json not present"));
        assertTrue(error.getMessage().contains("REQUIRE_ONTOLOGY_DOCS=true"));
    }

    @Test
    void missingInputSkipsOutsideTheGate() {
        for (String value : new String[] {null, "", "false", "1"}) {
            DeviceCatalogParityTest.envReader = () -> value;
            assertThrows(TestAbortedException.class, () -> DeviceCatalogParityTest.requirePresent(false, "missing"));
        }
    }

    @Test
    void presentInputPassesEitherWay() {
        for (String value : new String[] {"true", null}) {
            DeviceCatalogParityTest.envReader = () -> value;
            assertDoesNotThrow(() -> DeviceCatalogParityTest.requirePresent(true, "present"));
        }
    }
}
