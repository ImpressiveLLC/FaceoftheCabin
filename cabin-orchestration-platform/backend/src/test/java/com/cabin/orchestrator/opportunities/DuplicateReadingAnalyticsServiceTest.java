package com.cabin.orchestrator.opportunities;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DuplicateReadingAnalyticsServiceTest {

    private static DeviceStatus device(String id, boolean enabled, String parent, List<String> reports) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("enabled", enabled);
        attrs.put("reportsFields", reports);
        if (parent != null) attrs.put("parentDeviceId", parent);
        return new DeviceStatus(id, DeviceType.TEMPERATURE_SENSOR, id, "ONLINE", Instant.now(), attrs, "cabin");
    }

    private static Map<String, DeviceStatus> byId(DeviceStatus... devices) {
        Map<String, DeviceStatus> map = new HashMap<>();
        for (DeviceStatus d : devices) map.put(d.deviceId(), d);
        return map;
    }

    @Test
    void anyMeasurementAParentAndItsEnabledChildBothReportIsADuplicate() {
        DeviceStatus parent = device("z2m-kitchen", true, null, List.of("battery", "temperature", "humidity", "linkquality"));
        DeviceStatus temperature = device("ha-kitchen-t", true, "z2m-kitchen", List.of("temperature"));
        // A measurement nobody wrote a rule for is found the same way.
        DeviceStatus exotic = device("ha-kitchen-x", true, "z2m-kitchen", List.of("battery", "linkquality"));
        Map<String, DeviceStatus> all = byId(parent, temperature, exotic);

        Map<String, List<DuplicateReadingAnalyticsService.Duplicate>> found =
            DuplicateReadingAnalyticsService.findDuplicates(all.values(), all::get);

        assertThat(found).containsOnlyKeys("z2m-kitchen");
        assertThat(found.get("z2m-kitchen")).extracting(DuplicateReadingAnalyticsService.Duplicate::deviceId)
            .containsExactly("ha-kitchen-t", "ha-kitchen-x");
        assertThat(found.get("z2m-kitchen").get(0).fields()).containsExactly("temperature");
        // linkquality is shared too, but it describes the radio, not the world.
        assertThat(found.get("z2m-kitchen").get(1).fields()).containsExactly("battery");
    }

    @Test
    void aDisabledChildOrParentOrAnUnsharedReadingIsNotADuplicate() {
        DeviceStatus parent = device("z2m-a", true, null, List.of("temperature"));
        DeviceStatus disabledChild = device("ha-a-t", false, "z2m-a", List.of("temperature"));
        DeviceStatus differentReading = device("ha-a-p", true, "z2m-a", List.of("pressure"));
        DeviceStatus offParent = device("z2m-b", false, null, List.of("temperature"));
        DeviceStatus childOfOff = device("ha-b-t", true, "z2m-b", List.of("temperature"));
        Map<String, DeviceStatus> all = byId(parent, disabledChild, differentReading, offParent, childOfOff);

        assertThat(DuplicateReadingAnalyticsService.findDuplicates(all.values(), all::get)).isEmpty();
    }

    @Test
    void oneOpportunityPerParentIsSavedAndAResolutionHoldsUntilTheChildrenChange() {
        DeviceRegistry registry = mock(DeviceRegistry.class);
        JdbcOptimizationOpportunityStore store = mock(JdbcOptimizationOpportunityStore.class);
        when(store.findAll(OpportunityStatus.RESOLVED)).thenReturn(List.of());
        when(store.findOpenOrAcknowledged(any(), any())).thenReturn(java.util.Optional.empty());
        when(registry.get("z2m-kitchen")).thenReturn(device("z2m-kitchen", true, null, List.of("temperature")));
        DuplicateReadingAnalyticsService service = new DuplicateReadingAnalyticsService(registry, store);
        List<DuplicateReadingAnalyticsService.Duplicate> duplicates =
            List.of(new DuplicateReadingAnalyticsService.Duplicate("ha-kitchen-t", "Kitchen T", List.of("temperature")));

        service.evaluate("z2m-kitchen", duplicates);

        ArgumentCaptor<OptimizationOpportunity> saved = ArgumentCaptor.forClass(OptimizationOpportunity.class);
        verify(store).save(saved.capture());
        assertThat(saved.getValue().opportunityType()).isEqualTo(OpportunityType.DUPLICATE_READING);
        assertThat(saved.getValue().deviceId()).isEqualTo("z2m-kitchen");
        assertThat(saved.getValue().status()).isEqualTo(OpportunityStatus.OPEN);

        // Resolved by a person for exactly these children: not reopened.
        JdbcOptimizationOpportunityStore resolvedStore = mock(JdbcOptimizationOpportunityStore.class);
        when(resolvedStore.findAll(OpportunityStatus.RESOLVED)).thenReturn(List.of(new OptimizationOpportunity(
            "r1", OpportunityType.DUPLICATE_READING, "z2m-kitchen", Instant.now(),
            Map.of("duplicates", List.of(Map.of("deviceId", "ha-kitchen-t"))), OpportunityStatus.RESOLVED, Instant.now())));
        new DuplicateReadingAnalyticsService(registry, resolvedStore).evaluate("z2m-kitchen", duplicates);
        verify(resolvedStore, never()).save(any());

        // A new child repeating the parent is new information: it reopens.
        List<DuplicateReadingAnalyticsService.Duplicate> more = List.of(duplicates.get(0),
            new DuplicateReadingAnalyticsService.Duplicate("ha-kitchen-h", "Kitchen H", List.of("humidity")));
        new DuplicateReadingAnalyticsService(registry, resolvedStore).evaluate("z2m-kitchen", more);
        verify(resolvedStore).save(any());
    }

    @Test
    void disablingTheLastRepeatingServiceLeavesNothingToScanAndTheCardIsNotPromisedToVanish() {
        DeviceStatus parent = device("z2m-kitchen", true, null, List.of("temperature"));
        DeviceStatus child = device("ha-kitchen-t", false, "z2m-kitchen", List.of("temperature"));
        assertThat(DuplicateReadingAnalyticsService.findDuplicates(List.of(parent, child), byId(parent, child)::get)).isEmpty();

        DeviceRegistry registry = mock(DeviceRegistry.class);
        JdbcOptimizationOpportunityStore store = mock(JdbcOptimizationOpportunityStore.class);
        when(store.findAll(OpportunityStatus.RESOLVED)).thenReturn(List.of());
        when(store.findOpenOrAcknowledged(any(), any())).thenReturn(java.util.Optional.empty());
        when(registry.get("z2m-kitchen")).thenReturn(parent);
        new DuplicateReadingAnalyticsService(registry, store).evaluate("z2m-kitchen", List.of(
            new DuplicateReadingAnalyticsService.Duplicate("ha-kitchen-t", "Kitchen T", List.of("temperature"))));
        ArgumentCaptor<OptimizationOpportunity> saved = ArgumentCaptor.forClass(OptimizationOpportunity.class);
        verify(store).save(saved.capture());
        assertThat(String.valueOf(saved.getValue().evidence().get("recommendations")))
            .contains("mark this card resolved").doesNotContain("removed from this list");
    }
}
