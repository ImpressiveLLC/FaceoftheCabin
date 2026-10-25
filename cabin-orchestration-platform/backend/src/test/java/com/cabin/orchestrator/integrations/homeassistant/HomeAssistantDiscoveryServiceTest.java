package com.cabin.orchestrator.integrations.homeassistant;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Test
    void electricalServicesKeepTheirOwnQuantityAndNormalizeUnitsAtBothLocations() {
        for (String site : List.of("cabin", "home")) {
            when(adapter.discover(site)).thenReturn(List.of(
                new HomeAssistantAdapter.DiscoveredEntity("sensor.arbitrary_plug_power", "0", Map.of("device_class", "power", "unit_of_measurement", "kW")),
                new HomeAssistantAdapter.DiscoveredEntity("sensor.arbitrary_plug_energy", "48320", Map.of("device_class", "energy", "unit_of_measurement", "Wh")),
                new HomeAssistantAdapter.DiscoveredEntity("sensor.arbitrary_plug_voltage", "122.3", Map.of("device_class", "voltage", "unit_of_measurement", "V")),
                new HomeAssistantAdapter.DiscoveredEntity("number.power_threshold", "3200", Map.of("device_class", "power", "unit_of_measurement", "W"))));
            service.discoverLocation(site);
            assertThat(registry.get("ha-" + site + "-sensor-arbitrary-plug-power").attributes())
                .containsEntry("power", 0.0).containsEntry("reportsFields", List.of("power"));
            assertThat(registry.get("ha-" + site + "-sensor-arbitrary-plug-energy").attributes())
                .containsEntry("energy", 48.32).containsEntry("reportsFields", List.of("energy"));
            assertThat(registry.get("ha-" + site + "-sensor-arbitrary-plug-voltage").attributes())
                .containsEntry("voltage", 122.3).containsEntry("reportsFields", List.of("voltage"));
            assertThat(registry.get("ha-" + site + "-number-power-threshold").attributes())
                .doesNotContainKey("power").containsEntry("reportsFields", List.of());
        }
    }

    @Test
    void unavailableElectricalObservationDoesNotRetainLastValueAsCurrent() {
        when(adapter.discover("cabin")).thenReturn(List.of(new HomeAssistantAdapter.DiscoveredEntity(
            "sensor.plug_energy", "48.32", Map.of("device_class", "energy", "unit_of_measurement", "kWh"))));
        service.discoverLocation("cabin");
        when(adapter.discover("cabin")).thenReturn(List.of(new HomeAssistantAdapter.DiscoveredEntity(
            "sensor.plug_energy", "unavailable", Map.of("device_class", "energy", "unit_of_measurement", "kWh"))));
        service.discoverLocation("cabin");
        assertThat(registry.get("ha-cabin-sensor-plug-energy").attributes()).doesNotContainKey("energy");
    }
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

    // 2026-08-25: found live that a sensor-domain entity's real reading
    // (HA's own state field -- "72.5" for a temperature sensor) was never
    // captured anywhere. Confirmed against the real Kidde payload: only
    // device_class/unit_of_measurement/friendly_name were ever published,
    // never the number itself.
    @Test
    void aSensorDomainEntitysRawStateIsCapturedAsItsValue() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_indoor_temperature", "72.5",
                Map.of("friendly_name", "Kidde Indoor Temperature", "device_class", "temperature"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        List<DeviceStatus> discovered = registry.byLocation("cabin");
        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).attributes()).containsEntry("value", "72.5");
    }

    // 2026-09-14: found live that every HA-discovered candidate's self-
    // discovery lookup silently reported "nothing to search for" -- HA's
    // own per-entity attributes never included "vendor"/"model"/
    // "description", the exact keys DeviceDiscoveryController forwards to
    // cabin-discovery, unlike Zigbee2MqttAdapter's own attrs.
    @Test
    void descriptionIsPopulatedFromFriendlyNameAndDeviceClassSoDiscoveryHasSomethingToSearchFor() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "binary_sensor.motion_entry_occupancy", "off",
                Map.of("friendly_name", "motion_entry Occupancy", "device_class", "occupancy"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        List<DeviceStatus> discovered = registry.byLocation("cabin");
        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).attributes())
            .containsEntry("description", "motion_entry Occupancy (occupancy)");
    }

    @Test
    void descriptionFallsBackToJustFriendlyNameWhenNoDeviceClassIsReported() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "switch.smart_switch_breaker_box", "on",
                Map.of("friendly_name", "Breaker Box Switch"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        List<DeviceStatus> discovered = registry.byLocation("cabin");
        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).attributes()).containsEntry("description", "Breaker Box Switch");
    }

    // 2026-10-02 (W-27): a switch's on/off was collapsed to ONLINE and lost,
    // so the dashboard couldn't show or toggle it.
    @Test
    void aSwitchDomainEntitysOnOffIsCapturedAsStateInZigbeeCasing() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "switch.heater_mech_room", "on", Map.of("friendly_name", "Heater Mech Room")),
            new HomeAssistantAdapter.DiscoveredEntity(
                "switch.breaker_box", "unavailable", Map.of("friendly_name", "Breaker Box"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        Map<String, Map<String, Object>> byEntity = new java.util.HashMap<>();
        registry.byLocation("cabin").forEach(d -> byEntity.put((String) d.attributes().get("entityId"), d.attributes()));
        assertThat(byEntity.get("switch.heater_mech_room")).containsEntry("state", "ON");
        assertThat(byEntity.get("switch.breaker_box")).doesNotContainKey("state");
    }

    @Test
    void aNonSensorDomainEntityDoesNotGetAValueAttribute() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "binary_sensor.kidde_co_alarm", "off", Map.of("friendly_name", "Kidde CO Alarm"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        List<DeviceStatus> discovered = registry.byLocation("cabin");
        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).attributes()).doesNotContainKey("value");
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

    // 2026-08-27: closing the charting gap docs/MAINTENANCE.md flagged --
    // Kidde's CO2/air-quality entities inferred as generic
    // HOME_ASSISTANT_ENTITY (no device_class mapping existed) and their
    // reading was never normalized into the semantic key
    // CabinEventService.dailyAggregates()/SensorHistoryPanel actually query.

    @Test
    void carbonDioxideDeviceClassInfersCo2SensorType() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co2_level", "620",
                Map.of("friendly_name", "Kidde CO2 Level", "device_class", "carbon_dioxide"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(registry.byLocation("cabin")).extracting(DeviceStatus::type).containsExactly(DeviceType.CO2_SENSOR);
    }

    @Test
    void airQualityDeviceClassesInferAirQualitySensorType() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_tvoc", "310",
                Map.of("friendly_name", "Kidde TVOC", "device_class", "volatile_organic_compounds")),
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_air_quality_index", "42",
                Map.of("friendly_name", "Kidde Air Quality Index", "device_class", "aqi"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(registry.byLocation("cabin")).extracting(DeviceStatus::type)
            .containsExactlyInAnyOrder(DeviceType.AIR_QUALITY_SENSOR, DeviceType.AIR_QUALITY_SENSOR);
    }

    // A hypothetical vendor whose sensor DOES set unit_of_measurement=ppm
    // but no device_class -- kept as the generic, name-independent fallback
    // case (deliberately not keyed to "kidde" so any future integration with
    // this specific gap is covered too). See the test below for Kidde's own,
    // deeper real-world gap.
    @Test
    void ppmUnitWithNoDeviceClassInfersCoSensorTypeAsAVendorGapFallback() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.some_other_co_sensor", "3",
                Map.of("friendly_name", "Some Other CO Sensor", "unit_of_measurement", "ppm"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(registry.byLocation("cabin")).extracting(DeviceStatus::type).containsExactly(DeviceType.CO_SENSOR);
    }

    // WSJF bug #3: the ppm-unit test above was written assuming Kidde's real
    // CO-level entity sets unit_of_measurement=ppm -- verified live against
    // the actual M920q Postgres (cabin_event payloads, 2026-09-03) that this
    // was never true: BOTH device_class and unit_of_measurement are empty
    // for this exact entity. Neither prior fallback ever matched it, so it
    // silently classified as generic HOME_ASSISTANT_ENTITY this whole time --
    // no Monitoring tile, no "co" semantic field for Sensor History -- despite
    // reporting a real CO ppm value every cycle (only visible via Grafana's
    // raw-payload SQL, a separate path that bypasses this classification
    // entirely). This is the exact live payload shape; the name-match
    // fallback is what actually saves it.
    @Test
    void kiddesRealCoLevelEntityHasNoDeviceClassAndNoUnitButIsStillInferredAsCoSensor() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co_temp_and_humidity_cabin_upstairs_co_level", "3",
                Map.of("friendly_name", "Kidde CO Temp and Humidity Cabin Upstairs CO Level"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        List<DeviceStatus> devices = registry.byLocation("cabin");
        assertThat(devices).extracting(DeviceStatus::type).containsExactly(DeviceType.CO_SENSOR);
        // The whole point of the fix: a value now actually lands under the
        // "co" semantic field, matching D13/bug #3's own vocabulary rule
        // (co = carbon monoxide only, never carbon dioxide) -- this is the
        // literal "measurement_type for the Kidde device entity is co"
        // success criterion, exercised end to end, not asserted in isolation.
        assertThat(devices.get(0).attributes()).containsEntry("co", 3.0);
    }

    @Test
    void aTemperatureSensorsReadingIsAlsoStoredUnderItsSemanticFieldName() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_indoor_temperature", "72.5",
                Map.of("friendly_name", "Kidde Indoor Temperature", "device_class", "temperature"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        Map<String, Object> attrs = registry.byLocation("cabin").get(0).attributes();
        assertThat(attrs).containsEntry("value", "72.5").containsEntry("temperature", 72.5);
    }

    // Found live, 2026-08-27, from real device tiles reading 149-162°F for
    // an actual ~65-72°F cabin: this HA instance reports temperature
    // sensors in °F (confirmed via unit_of_measurement), but the app-wide
    // convention (native Zigbee2MQTT payloads) assumes "temperature" is
    // always Celsius. Storing the raw °F number unconverted meant the
    // frontend's Celsius->display-unit conversion applied a second time.
    @Test
    void aFahrenheitTemperatureReadingIsConvertedToCelsiusBeforeStorage() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.mech_room_temp_and_humidity_temperature", "65.12",
                Map.of("friendly_name", "Mech Room Temperature", "device_class", "temperature",
                    "unit_of_measurement", "°F"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        Map<String, Object> attrs = registry.byLocation("cabin").get(0).attributes();
        assertThat(attrs).containsEntry("value", "65.12");
        assertThat((Double) attrs.get("temperature")).isCloseTo(18.4, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void aCelsiusTemperatureReadingIsStoredAsIsNotConverted() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_indoor_temperature", "22.3",
                Map.of("friendly_name", "Kidde Indoor Temperature", "device_class", "temperature",
                    "unit_of_measurement", "°C"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        Map<String, Object> attrs = registry.byLocation("cabin").get(0).attributes();
        assertThat(attrs).containsEntry("temperature", 22.3);
    }

    @Test
    void aCo2SensorsReadingIsAlsoStoredUnderItsSemanticFieldName() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co2_level", "620",
                Map.of("friendly_name", "Kidde CO2 Level", "device_class", "carbon_dioxide"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        Map<String, Object> attrs = registry.byLocation("cabin").get(0).attributes();
        assertThat(attrs).containsEntry("value", "620").containsEntry("co2", 620.0);
    }

    // A brief cloud-poll gap ("unavailable"/"unknown") must not pollute the
    // semantic field with a non-numeric value -- the next poll cycle
    // retries rather than this discovery cycle failing or storing garbage.
    @Test
    void aNonNumericStateIsNotStoredUnderTheSemanticField() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "sensor.kidde_co2_level", "unavailable",
                Map.of("friendly_name", "Kidde CO2 Level", "device_class", "carbon_dioxide"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        Map<String, Object> attrs = registry.byLocation("cabin").get(0).attributes();
        assertThat(attrs).containsEntry("value", "unavailable").doesNotContainKey("co2");
    }

    @Test
    void aLockDomainEntityDoesNotGetASemanticFieldStamped() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity(
                "lock.front_door", "locked", Map.of("friendly_name", "Front Door"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        Map<String, Object> attrs = registry.byLocation("cabin").get(0).attributes();
        assertThat(attrs).doesNotContainKeys("temperature", "humidity", "co2", "co", "airQualityIndex");
    }

    // 2026-10-16: Home runs Zigbee2MQTT on a phone collector that publishes HA
    // discovery to the shared broker, so the one HA instance on the M920q sees
    // Home devices and every cabin pass imported them as location "cabin"
    // duplicates of the native z2m-home-* device.
    private void registerNativeZigbee(String deviceId, String friendlyName, String prefix, String location) {
        registry.registerCandidate(new DeviceDescriptor(deviceId, friendlyName, DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", prefix + friendlyName, false, location), Map.of());
    }

    @Test
    void haEntitiesOfAHomeZigbeeDeviceAreNotImportedAsMislabeledCabinCandidates() {
        registerNativeZigbee("z2m-home-0x70d07efffee966f4", "0x70d07efffee966f4", "home_z2m/", "home");
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.0x70d07efffee966f4_temperature", "18.2",
                Map.of("friendly_name", "0x70d07efffee966f4 Temperature", "device_class", "temperature")),
            new HomeAssistantAdapter.DiscoveredEntity("sensor.0x70d07efffee966f4_battery", "100",
                Map.of("friendly_name", "0x70d07efffee966f4 Battery", "device_class", "battery"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(registry.byLocation("cabin")).extracting(DeviceStatus::deviceId)
            .noneMatch(id -> id.contains("70d07"));
        assertThat(registry.byLocation("home")).extracting(DeviceStatus::deviceId)
            .contains("z2m-home-0x70d07efffee966f4")
            .noneMatch(id -> id.startsWith("ha-") && id.contains("70d07"));
    }

    @Test
    void haEntitiesOfACabinZigbeeDeviceAreStillImportedExactlyAsBefore() {
        registerNativeZigbee("z2m-temp_kitchen", "temp_kitchen", "zigbee2mqtt/", "cabin");
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.temp_kitchen_temperature", "21.0",
                Map.of("friendly_name", "temp_kitchen Temperature", "device_class", "temperature"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(registry.byLocation("cabin")).extracting(DeviceStatus::deviceId)
            .contains("ha-cabin-sensor-temp-kitchen-temperature");
    }

    @Test
    void theLongestMatchingNativeNameDecidesWhichLocationOwnsAnHaEntity() {
        registerNativeZigbee("z2m-temp", "temp", "zigbee2mqtt/", "cabin");
        registerNativeZigbee("z2m-home-temp_kitchen", "temp_kitchen", "home_z2m/", "home");
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.temp_kitchen_humidity", "50",
                Map.of("friendly_name", "temp_kitchen Humidity", "device_class", "humidity")),
            new HomeAssistantAdapter.DiscoveredEntity("sensor.temp_outside_humidity", "60",
                Map.of("friendly_name", "temp_outside Humidity", "device_class", "humidity"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(registry.byLocation("cabin")).extracting(DeviceStatus::deviceId)
            .contains("ha-cabin-sensor-temp-outside-humidity")
            .doesNotContain("ha-cabin-sensor-temp-kitchen-humidity");
    }

    @Test
    void aFriendlyNameSharedByTwoLocationsIsAmbiguousAndNeverSkipsAnHaEntity() {
        registerNativeZigbee("z2m-hall", "hall", "zigbee2mqtt/", "cabin");
        registerNativeZigbee("z2m-home-hall", "hall", "home_z2m/", "home");
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.hall_temperature", "20",
                Map.of("friendly_name", "hall Temperature", "device_class", "temperature"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());

        service.discoverLocation("cabin");

        assertThat(registry.byLocation("cabin")).extracting(DeviceStatus::deviceId)
            .contains("ha-cabin-sensor-hall-temperature");
    }

    // W-88 (2026-10-21, cabin dehumidifier switch + attic sensor): the native Zigbee device is the
    // parent and its Home Assistant entities are its services. Before this, a person had to draw each
    // link by hand, and the attic sensor had been linked the wrong way round (the device under its
    // own humidity entity), so it was charted once per device id.
    private void registerCabinNative(String friendlyName) {
        registerCabinNative(friendlyName, List.of("power", "voltage", "current", "energy", "temperature", "humidity", "battery"));
    }

    private void registerCabinNative(String friendlyName, List<String> reportedFields) {
        registry.registerCandidate(new DeviceDescriptor("z2m-" + friendlyName, friendlyName, DeviceType.POWER_METER,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/" + friendlyName, false, "cabin"),
            Map.of("vendorReportedFields", reportedFields));
    }

    private void discoverSwitchAndPower(String friendly, String switchState, String watts) {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("switch." + friendly, switchState,
                Map.of("friendly_name", friendly)),
            new HomeAssistantAdapter.DiscoveredEntity("sensor." + friendly + "_power", watts,
                Map.of("friendly_name", friendly + " Power", "device_class", "power", "unit_of_measurement", "W"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());
    }

    private static String parentOf(DeviceStatus status) {
        Object parent = status.attributes().get("parentDeviceId");
        return parent == null ? "" : String.valueOf(parent);
    }

    @Test
    void homeAssistantEntitiesOfACabinZigbeeDeviceAreLinkedUnderIt() {
        registerCabinNative("0xa4c1381f3884ffff");
        discoverSwitchAndPower("0xa4c1381f3884ffff", "on", "412");

        service.discoverLocation("cabin");

        assertThat(parentOf(registry.get("ha-cabin-switch-0xa4c1381f3884ffff"))).isEqualTo("z2m-0xa4c1381f3884ffff");
        assertThat(parentOf(registry.get("ha-cabin-sensor-0xa4c1381f3884ffff-power"))).isEqualTo("z2m-0xa4c1381f3884ffff");
        assertThat(parentOf(registry.get("z2m-0xa4c1381f3884ffff"))).isEmpty();
        // Linking is not acceptance: the entities stay candidates.
        assertThat(registry.lifecycleState("ha-cabin-switch-0xa4c1381f3884ffff").name()).isEqualTo("CANDIDATE");
    }

    @Test
    void aLinkDrawnTheWrongWayRoundIsRepairedSoTheZigbeeDeviceIsTheParent() {
        registerCabinNative("0xb0e8e8fffef4e8ae");
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.0xb0e8e8fffef4e8ae_humidity", "53.6",
                Map.of("friendly_name", "Attic Humidity", "device_class", "humidity")),
            new HomeAssistantAdapter.DiscoveredEntity("sensor.0xb0e8e8fffef4e8ae_temperature", "14.5",
                Map.of("friendly_name", "Attic Temperature", "device_class", "temperature"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());
        service.discoverLocation("cabin");
        // Reproduce the live state: the device sits under its own humidity entity, which parents the temperature one.
        registry.setDiscoveredParent("ha-cabin-sensor-0xb0e8e8fffef4e8ae-humidity", "");
        registry.setDiscoveredParent("z2m-0xb0e8e8fffef4e8ae", "ha-cabin-sensor-0xb0e8e8fffef4e8ae-humidity");
        registry.setDiscoveredParent("ha-cabin-sensor-0xb0e8e8fffef4e8ae-temperature", "ha-cabin-sensor-0xb0e8e8fffef4e8ae-humidity");

        service.discoverLocation("cabin");

        assertThat(parentOf(registry.get("z2m-0xb0e8e8fffef4e8ae"))).isEmpty();
        assertThat(parentOf(registry.get("ha-cabin-sensor-0xb0e8e8fffef4e8ae-humidity"))).isEqualTo("z2m-0xb0e8e8fffef4e8ae");
        assertThat(parentOf(registry.get("ha-cabin-sensor-0xb0e8e8fffef4e8ae-temperature"))).isEqualTo("z2m-0xb0e8e8fffef4e8ae");
    }

    @Test
    void aParentAPersonChoseElsewhereIsLeftAlone() {
        registerCabinNative("0xa4c1381f3884ffff");
        registry.registerCandidate(new DeviceDescriptor("ha-cabin-other", "Other", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.TELEMETRY), "ha_rest", "switch.other", false, "cabin"), Map.of());
        discoverSwitchAndPower("0xa4c1381f3884ffff", "on", "412");
        service.discoverLocation("cabin");
        registry.setDiscoveredParent("ha-cabin-sensor-0xa4c1381f3884ffff-power", "ha-cabin-other");

        service.discoverLocation("cabin");

        assertThat(parentOf(registry.get("ha-cabin-sensor-0xa4c1381f3884ffff-power"))).isEqualTo("ha-cabin-other");
    }

    @Test
    void aSensorEntityOfAZigbeeDeviceDoesNotLogASecondTelemetrySeriesButASwitchStillDoes() {
        registerCabinNative("0xa4c1381f3884ffff");
        discoverSwitchAndPower("0xa4c1381f3884ffff", "on", "412");
        service.discoverLocation("cabin");
        eventPublisher.published.clear();
        // The first sight of an entity never publishes, so change both values on the second cycle.
        discoverSwitchAndPower("0xa4c1381f3884ffff", "off", "418");

        service.discoverLocation("cabin");

        assertThat(eventPublisher.published).extracting(CabinEvent::sourceDeviceId)
            .contains("ha-cabin-switch-0xa4c1381f3884ffff")
            .doesNotContain("ha-cabin-sensor-0xa4c1381f3884ffff-power");
        // The live reading itself is still kept on the entity.
        assertThat(registry.get("ha-cabin-sensor-0xa4c1381f3884ffff-power").attributes().get("value")).isEqualTo("418");
    }

    // C-A1-1 (W-94): a duplicate humidity entity is skipped; a derived entity under the same device is not.
    @Test
    void aDuplicateHumidityEntityIsSkippedButADerivedDewPointEntityKeepsLogging() {
        registerCabinNative("0xb0e8e8fffef4e8ae", List.of("battery", "temperature", "humidity"));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());
        when(adapter.discover("cabin")).thenReturn(attic("53.6", "9.1"));
        service.discoverLocation("cabin");
        eventPublisher.published.clear();
        when(adapter.discover("cabin")).thenReturn(attic("54.2", "9.4"));

        service.discoverLocation("cabin");

        assertThat(eventPublisher.published).extracting(CabinEvent::sourceDeviceId)
            .contains("ha-cabin-sensor-0xb0e8e8fffef4e8ae-dew-point")
            .doesNotContain("ha-cabin-sensor-0xb0e8e8fffef4e8ae-humidity");
        // Both are linked under the Zigbee device either way.
        assertThat(parentOf(registry.get("ha-cabin-sensor-0xb0e8e8fffef4e8ae-dew-point"))).isEqualTo("z2m-0xb0e8e8fffef4e8ae");
    }

    @Test
    void aSensorOfAMeasurementTheNativeDeviceDoesNotReportKeepsLogging() {
        registerCabinNative("0xb0e8e8fffef4e8ae", List.of("battery", "temperature"));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());
        when(adapter.discover("cabin")).thenReturn(attic("53.6", "9.1"));
        service.discoverLocation("cabin");
        eventPublisher.published.clear();
        when(adapter.discover("cabin")).thenReturn(attic("54.2", "9.4"));

        service.discoverLocation("cabin");

        assertThat(eventPublisher.published).extracting(CabinEvent::sourceDeviceId)
            .contains("ha-cabin-sensor-0xb0e8e8fffef4e8ae-humidity");
    }

    private static List<HomeAssistantAdapter.DiscoveredEntity> attic(String humidity, String dewPoint) {
        return List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.0xb0e8e8fffef4e8ae_humidity", humidity,
                Map.of("friendly_name", "Attic Humidity", "device_class", "humidity")),
            new HomeAssistantAdapter.DiscoveredEntity("sensor.0xb0e8e8fffef4e8ae_dew_point", dewPoint,
                Map.of("friendly_name", "Attic Dew Point", "unit_of_measurement", "C")));
    }

    @Test
    void anEntityOfADeviceThatIsNotNativeZigbeeStillPublishesItsOwnTelemetry() {
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.kidde_co", "5",
                Map.of("friendly_name", "Kidde CO", "device_class", "carbon_monoxide"))));
        when(adapter.deviceIdsByEntity("cabin")).thenReturn(Map.of());
        service.discoverLocation("cabin");
        eventPublisher.published.clear();
        when(adapter.discover("cabin")).thenReturn(List.of(
            new HomeAssistantAdapter.DiscoveredEntity("sensor.kidde_co", "9",
                Map.of("friendly_name", "Kidde CO", "device_class", "carbon_monoxide"))));

        service.discoverLocation("cabin");

        assertThat(eventPublisher.published).extracting(CabinEvent::sourceDeviceId)
            .contains("ha-cabin-sensor-kidde-co");
    }
}
