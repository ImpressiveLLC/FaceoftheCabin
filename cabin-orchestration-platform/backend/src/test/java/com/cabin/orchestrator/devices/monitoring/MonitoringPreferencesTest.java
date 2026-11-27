package com.cabin.orchestrator.devices.monitoring;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringPreferencesTest {
    @Test void preferencesCannotCreateCardsOutsideExistingProjection() {
        var preferences = new MonitoringPreferences("cabin", 3, List.of(
            new MonitoringPreferences.Card("unknown", true),
            new MonitoringPreferences.Card("disabled-parent-with-active-child", true),
            new MonitoringPreferences.Card("hidden", false)));
        var shadow = preferences.shadow(List.of("hidden", "disabled-parent-with-active-child", "new"),
            Set.of("hidden", "disabled-parent-with-active-child"));
        assertEquals(List.of("disabled-parent-with-active-child", "new"), shadow.visibleIds());
        assertEquals(List.of("unknown"), shadow.ignoredPreferenceIds());
        assertEquals(List.of("new"), shadow.missingSpecifications());
        assertTrue(shadow.changed());
    }

    @Test void duplicateProjectionIsReportedAndDefaultOrderPreserved() {
        var preferences = new MonitoringPreferences("home", 0, List.of());
        assertEquals(List.of("a"), preferences.shadow(List.of("a", "a"), Set.of("a")).collisions());
        assertFalse(preferences.shadow(List.of("a", "b"), Set.of("a", "b")).changed());
    }
}
