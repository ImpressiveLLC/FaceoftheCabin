package com.cabin.orchestrator.integrations.homeassistant;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 2026-08-18: covers two real gaps found live on the M920q while
 * diagnosing why the Liebherr fridge and Kidde "show sometimes, under
 * some groups, inconsistently" and can't be permanently configured --
 * (1) DEVICE_DOMAINS silently dropped number/select entities forever
 * (2 of the fridge's setpoints, its icemaker mode) with no error, and
 * (2) up to 6 of the fridge's remaining entities each surfaced as their
 * own unrelated candidate with nothing tying them together. Neither of
 * these tests hits real HTTP -- HomeAssistantAdapter is mocked, matching
 * how BlinkMotionWebhookControllerTest/CameraMediaControllerTest already
 * mock their own collaborators in this codebase.
 */
class HomeAssistantDiscoveryServiceTest {

    private static class RecordingEventPublisher extends EventPublisher {
        final List<CabinEvent> published = new ArrayList<>();
        @Override public void publish(CabinEvent event) { published.add(event); }
    }

    private HomeAssistantAdapter adapter;
    private DeviceRegistry registry;
    private RecordingEventPublisher eventPublisher;
    private HomeAssistantDiscoveryService service;

    @BeforeEach
    void setUp() {
        adapter = mock(HomeAssistantAdapter.class);
        registry = new DeviceRegistry(List.of());
        PresenceSignalRegistry presenceSignals = new PresenceSignalRegistry();
        PresenceService presenceService = mock(PresenceService.class);
        eventPublisher = new RecordingEventPublisher();
        service = new HomeAssistantDiscoveryService(adapter, registry, presenceSignals, presenceService, eventPublisher);
        when(adapter.normalizedState(org.mockito.ArgumentMatchers.anyString())).thenReturn("ONLINE");
    }

    @Test
    void numberAndSelectDomainsAreNowDiscoveredInsteadOfSilentlySkipped() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "number.loonie_mc_frigerton_top_zone_setpoint", "37", Map.of("friendly_name", "Top Zone Setpoint")),
            new HomeAssistantAdapter.DiscoveredEntity(
                "select.loonie_mc_frigerton_bottom_zone_icemaker", "on", Map.of("friendly_name", "Icemaker"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        List<DeviceStatus> discovered = registry.byLocation("cabin");
        assertThat(discovered).extracting(DeviceStatus::deviceId)
            .anyMatch(id -> id.contains("top-zone-setpoint"))
            .anyMatch(id -> id.contains("icemaker"));
    }

    @Test
    void siblingEntitiesSharingAHaDeviceIdGetTheSameAttributeStamped() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "switch.loonie_mc_frigerton_partymode", "off", Map.of("friendly_name", "Party Mode")),
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.loonie_mc_frigerton_top_zone", "37", Map.of("friendly_name", "Top Zone Temp"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of(
            "switch.loonie_mc_frigerton_partymode", "ha-device-liebherr-123",
            "sensor.loonie_mc_frigerton_top_zone", "ha-device-liebherr-123"));

        service.discoverLocation("cabin");

        List<DeviceStatus> discovered = registry.byLocation("cabin");
        assertThat(discovered).hasSize(2);
        assertThat(discovered).allSatisfy(d ->
            assertThat(d.attributes()).containsEntry("haDeviceId", "ha-device-liebherr-123"));
    }

    @Test
    void anEntityWithNoResolvedDeviceIdIsDiscoveredWithoutTheAttributeInsteadOfFailing() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.some_helper_entity", "42", Map.of("friendly_name", "A Helper"))));
        // Real behavior when the bulk lookup fails entirely (blank token,
        // unreachable HA) or a specific entity genuinely has no device --
        // discovery must still proceed exactly as it did before haDeviceId
        // existed, not skip the entity or throw.
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        List<DeviceStatus> discovered = registry.byLocation("cabin");
        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).attributes()).doesNotContainKey("haDeviceId");
    }

    // 2026-08-21 (E5) -- the poll-to-event bridge. Before this,
    // HomeAssistantAdapter/HomeAssistantDiscoveryService had no
    // CabinEvent/eventPublisher reference at all, so WorkflowRuleService
    // could never react to Kidde/Liebherr/any HA entity changing.

    @Test
    void firstDiscoveryOfAnEntityPublishesNothing() {
        // registerCandidate() already syncs the device's attrs on first
        // sight -- current.attributes() equals the freshly-merged attrs by
        // the time publishIfChanged() runs, so there's genuinely nothing
        // to diff against yet. Matches Device Manager's own candidate flow
        // being the real "something new showed up" surface, not this
        // trigger.
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co", "37", Map.of("friendly_name", "Kidde CO"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(eventPublisher.published).isEmpty();
    }

    @Test
    void anUnchangedPollCyclePublishesNothing() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co", "37", Map.of("friendly_name", "Kidde CO"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());
        service.discoverLocation("cabin"); // baseline (first-sight, asserted empty above)
        eventPublisher.published.clear();

        service.discoverLocation("cabin"); // identical data, real ~60s-later cycle

        assertThat(eventPublisher.published).isEmpty();
    }

    @Test
    void aChangedAttributePublishesExactlyOneTelemetryEventWithTheRealAttrsAndHaState() {
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co", "37", Map.of("friendly_name", "Kidde CO", "co_ppm", 0))));
        service.discoverLocation("cabin"); // baseline
        eventPublisher.published.clear();
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co", "37", Map.of("friendly_name", "Kidde CO", "co_ppm", 12))));

        service.discoverLocation("cabin");

        assertThat(eventPublisher.published).hasSize(1);
        CabinEvent event = eventPublisher.published.get(0);
        assertThat(event.eventType()).isEqualTo("TELEMETRY");
        assertThat(event.payload()).containsEntry("co_ppm", 12).containsEntry("haState", "ONLINE");
    }
}
