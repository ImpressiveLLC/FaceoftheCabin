package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.OccupancyEdges;
import com.cabin.orchestrator.events.OccupancyHistory;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.signalquality.SignalQualityRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Targeted coverage for the 2026-08-08 SignalQualityRegistry wiring --
 * see that class's own comment for the full prototype reasoning. Not a
 * full test suite for Zigbee2MqttAdapter (that class had zero test
 * coverage before this and gaining it wholesale is a separate task);
 * this only covers the one new integration point this session added.
 */
class Zigbee2MqttAdapterTest {

    private DeviceRegistry registry;
    private SignalQualityRegistry signalQualityRegistry;
    private Zigbee2MqttAdapter adapter;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        signalQualityRegistry = new SignalQualityRegistry();
        adapter = new Zigbee2MqttAdapter(registry, new EventPublisher(), signalQualityRegistry);
    }

    private void deliver(String topic, String payload) throws Exception {
        adapter.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    /** Matches real Z2M startup order: bridge/devices always arrives before any device state message. */
    private void registerDevice(String friendlyName) throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"%s","type":"EndDevice","definition":{
              "model":"SNZB-03PR2","description":"motion","vendor":"SONOFF","exposes":[]}}]
            """.formatted(friendlyName));
    }

    @Test
    void deviceStateWithLinkqualityIsRecordedInSignalQualityRegistry() throws Exception {
        registerDevice("motion_entry");

        deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 160, \"battery\": 100}");

        var a = signalQualityRegistry.assess("z2m-motion_entry");
        assertTrue(a.isPresent(), "a device message with linkquality must be recorded");
        assertEquals(160, a.get().current());
    }

    @Test
    void repeatedMessagesAccumulateHistoryNotJustTheLatestValue() throws Exception {
        registerDevice("motion_entry");

        for (int i = 0; i < 6; i++) {
            deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 200}");
        }

        var a = signalQualityRegistry.assess("z2m-motion_entry").orElseThrow();
        assertEquals(6, a.sampleCount(), "each message must add to history, not overwrite it");
    }

    @Test
    void aMessageWithoutLinkqualityIsNotRecorded() throws Exception {
        registerDevice("motion_entry");

        deliver("zigbee2mqtt/motion_entry", "{\"battery\": 100}");

        assertTrue(signalQualityRegistry.assess("z2m-motion_entry").isEmpty(),
            "a message with no linkquality field must not create a bogus reading");
    }

    // 2026-09-01: found via a real production incident -- a cabin-backend
    // redeploy resubscribed this adapter to zigbee2mqtt/#, Mosquitto replayed
    // main_water_valve's old retained state (silent since 2026-08-21), and
    // this handler stamped it with Instant.now() same as a live message,
    // making a physically dead device briefly show "back online" for one
    // health-check cycle before going stale again. See messageArrived's own
    // comment.
    @Test
    void aRetainedReplayPreservesThePriorLastSeenInsteadOfResettingTheStalenessClock() throws Exception {
        registerDevice("motion_entry");

        deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 160}");
        var lastSeenAfterLiveMessage = registry.get("z2m-motion_entry").lastSeen();

        Thread.sleep(20);
        MqttMessage retainedReplay = new MqttMessage("{\"linkquality\": 100}".getBytes());
        retainedReplay.setRetained(true);
        adapter.messageArrived("zigbee2mqtt/motion_entry", retainedReplay);

        assertEquals(lastSeenAfterLiveMessage, registry.get("z2m-motion_entry").lastSeen(),
            "a retained replay is the broker handing back its last cached value on resubscribe, " +
            "not proof the device said anything just now -- it must not reset lastSeen");
        // The replay's own payload is still applied to attributes/state --
        // only the staleness clock is protected, not the cached values.
        assertEquals(100, signalQualityRegistry.assess("z2m-motion_entry").orElseThrow().current());
    }

    @Test
    void aLiveMessageAfterARetainedReplayStillAdvancesLastSeenNormally() throws Exception {
        registerDevice("motion_entry");

        MqttMessage retainedReplay = new MqttMessage("{\"linkquality\": 100}".getBytes());
        retainedReplay.setRetained(true);
        adapter.messageArrived("zigbee2mqtt/motion_entry", retainedReplay);
        var lastSeenAfterReplay = registry.get("z2m-motion_entry").lastSeen();

        Thread.sleep(20);
        deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 160}");

        assertTrue(registry.get("z2m-motion_entry").lastSeen().isAfter(lastSeenAfterReplay),
            "a genuinely live message (not retained) must still advance lastSeen normally");
    }

    // Found 2026-09-11: cabin's real zigbee2mqtt/bridge/state sat retained
    // "offline" for 4+ days while the bridge was demonstrably healthy and
    // actively publishing all 13 devices' telemetry the entire time -- see
    // this class's own comment on lastBridgeHealthOnlineAt for the full
    // incident writeup and docs/MAINTENANCE.md for the operational entry.
    @Test
    void aLiveBridgeHealthPingOverridesAStaleRetainedOfflineState() throws Exception {
        deliver("zigbee2mqtt/bridge/state", "{\"state\":\"offline\"}");
        assertEquals("offline", adapter.getBridgeState(), "sanity check: raw retained value is offline before any health ping");

        deliver("zigbee2mqtt/bridge/health", "{\"mqtt\":{\"connected\":true}}");

        assertEquals("online", adapter.getBridgeState(),
            "a fresh bridge/health ping reporting mqtt.connected=true must override a stuck retained offline");
    }

    @Test
    void aRetainedBridgeHealthPingIsIgnored() throws Exception {
        deliver("zigbee2mqtt/bridge/state", "{\"state\":\"offline\"}");

        MqttMessage retainedHealth = new MqttMessage("{\"mqtt\":{\"connected\":true}}".getBytes());
        retainedHealth.setRetained(true);
        adapter.messageArrived("zigbee2mqtt/bridge/health", retainedHealth);

        assertEquals("offline", adapter.getBridgeState(),
            "bridge/health is only trustworthy because it's a fresh report -- a retained replay proves nothing");
    }

    @Test
    void aBridgeHealthPingReportingMqttDisconnectedDoesNotForceOnline() throws Exception {
        deliver("zigbee2mqtt/bridge/state", "{\"state\":\"offline\"}");

        deliver("zigbee2mqtt/bridge/health", "{\"mqtt\":{\"connected\":false}}");

        assertEquals("offline", adapter.getBridgeState());
    }

    @Test
    void aGenuineOnlineBridgeStateIsReportedDirectlyWithNoHealthPingNeeded() throws Exception {
        deliver("zigbee2mqtt/bridge/state", "{\"state\":\"online\"}");

        assertEquals("online", adapter.getBridgeState());
    }

    @Test
    void anUnregisteredDevicesStateMessageIsIgnoredEntirely() throws Exception {
        // No registerDevice() call -- messageArrived's own routing requires
        // the friendly name to already be known (bridge/devices), matching
        // real Z2M behavior where bridge/devices always arrives first.
        deliver("zigbee2mqtt/never_registered", "{\"linkquality\": 160}");

        assertTrue(signalQualityRegistry.assess("z2m-never_registered").isEmpty());
    }

    // Home's Termux collector (docs/RUNLOG_2026-09-10_home-collector-mr5u-termux.md)
    // shares this same broker with cabin's real Z2M bridge -- this proves a
    // second bridge on its own topic prefix stays fully independent, even
    // when both meshes happen to use the same friendly name. Cabin's device
    // must keep its original unqualified id (every already-persisted cabin
    // device id depends on that); home's gets a location-qualified id
    // instead of colliding with it in DeviceRegistry.
    @Test
    void secondBridgeKeepsHomeDevicesSeparateFromCabinEvenWithAClashingFriendlyName() throws Exception {
        adapter.configureBridgesForTest("zigbee2mqtt,zigbee2mqtt_home", "cabin,home");

        registerDevice("motion_entry"); // cabin, via the default "zigbee2mqtt/" prefix

        deliver("zigbee2mqtt_home/bridge/devices", """
            [{"friendly_name":"motion_entry","type":"EndDevice","definition":{
              "model":"SNZB-03PR2","description":"motion","vendor":"SONOFF","exposes":[]}}]
            """);

        assertNotNull(registry.get("z2m-motion_entry"), "cabin's device keeps its original unqualified id");
        assertNotNull(registry.get("z2m-home-motion_entry"), "home's device gets a location-qualified id instead of colliding");
        assertEquals("cabin", registry.descriptor("z2m-motion_entry").orElseThrow().location());
        assertEquals("home", registry.descriptor("z2m-home-motion_entry").orElseThrow().location());

        deliver("zigbee2mqtt/motion_entry", "{\"linkquality\": 90}");
        deliver("zigbee2mqtt_home/motion_entry", "{\"linkquality\": 150}");

        assertEquals(90, signalQualityRegistry.assess("z2m-motion_entry").orElseThrow().current(),
            "cabin's reading must not be overwritten by home's message on the same friendly name");
        assertEquals(150, signalQualityRegistry.assess("z2m-home-motion_entry").orElseThrow().current());
    }

    @Test
    void repeatedBridgeListCorrectsStaleCandidateMetadata() throws Exception {
        registry.registerCandidate(new DeviceDescriptor(
            "z2m-temp_kitchen", "main_water_valve", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND), "mqtt", "zigbee2mqtt/wrong", false, "cabin"),
            Map.of("model", "wrong"));

        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"temp_kitchen","type":"EndDevice","definition":{
              "model":"SNZB-02D","description":"temperature sensor","vendor":"SONOFF",
              "exposes":[{"type":"numeric","property":"temperature","access":1}]}}]
            """);

        var descriptor = registry.descriptor("z2m-temp_kitchen").orElseThrow();
        var status = registry.get("z2m-temp_kitchen");
        assertEquals("temp_kitchen", descriptor.name());
        assertEquals(DeviceType.TEMPERATURE_SENSOR, descriptor.type());
        assertEquals("zigbee2mqtt/temp_kitchen", descriptor.connectionString());
        assertEquals("temp_kitchen", status.name());
        assertEquals(DeviceType.TEMPERATURE_SENSOR, status.type());
        assertEquals("SNZB-02D", status.attributes().get("model"));
        assertEquals(true, status.attributes().get("candidate"));
    }

    @Test
    void mainsPowerRediscoveryClearsBatteryCheckinOverride() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"motion_entry","type":"EndDevice","power_source":"battery","definition":{
              "model":"SNZB-03PR2","description":"motion","vendor":"SONOFF","exposes":[]}}]
            """);
        assertEquals(1560, registry.get("z2m-motion_entry").attributes().get("expectedCheckinMinutes"));

        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"motion_entry","type":"Router","power_source":"mains","definition":{
              "model":"SNZB-03PR2","description":"motion","vendor":"SONOFF","exposes":[]}}]
            """);

        assertFalse(registry.get("z2m-motion_entry").attributes().containsKey("expectedCheckinMinutes"));
    }

    // D7 (docs/ontology/DECISIONS.md), Option B -- extractVendorReportedFields().
    // Fixture is the REAL exposes[] confirmed live against z2m-temp_kitchen
    // (SNZB-02WD) 2026-08-29, not a simplified guess: a naive "every numeric
    // expose" mapping would have wrongly included temperature_calibration/
    // humidity_calibration (category "config" -- a writable setting, not a
    // reported measurement) and linkquality (a real diagnostic value, but
    // not a measurement type this schema models). voltage used to be
    // excluded on that same reasoning -- D15 (2026-09-05) ratified it as a
    // real measurement type for power-monitoring devices, so it's now kept,
    // same as battery already was.
    @Test
    void vendorReportedFieldsExcludesConfigAndNonVocabularyDiagnosticExposes() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"temp_kitchen","type":"EndDevice","definition":{
              "model":"SNZB-02WD","vendor":"SONOFF","description":"Waterproof temperature and humidity sensor",
              "exposes":[
                {"type":"numeric","name":"battery","property":"battery","category":"diagnostic"},
                {"type":"numeric","name":"voltage","property":"voltage","category":"diagnostic"},
                {"type":"numeric","name":"temperature","property":"temperature"},
                {"type":"numeric","name":"humidity","property":"humidity"},
                {"type":"enum","name":"temperature_units","property":"temperature_units","category":"config"},
                {"type":"numeric","name":"temperature_calibration","property":"temperature_calibration","category":"config"},
                {"type":"numeric","name":"humidity_calibration","property":"humidity_calibration","category":"config"},
                {"type":"numeric","name":"linkquality","property":"linkquality","category":"diagnostic"}
              ]}}]
            """);

        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) registry.get("z2m-temp_kitchen").attributes().get("vendorReportedFields");
        // Order follows the exposes[] array itself (battery/voltage appear
        // before temperature/humidity in the real fixture) -- this asserts
        // the filtering, not a re-sort the method doesn't claim to do.
        assertEquals(List.of("battery", "voltage", "temperature", "humidity"), fields);
    }

    // 2026-10-21 (found on the cabin dehumidifier switch, SONOFF S61SZBTPB): Z2M puts a
    // controllable device's on/off under a "switch" expose whose own access flag is absent, so no
    // COMMAND capability was inferred; and because its description says "switch" it was typed as a
    // generic entity although it reports power, which hid it from the power tile and monitoring.
    private void registerPowerSwitch(String friendlyName) throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"%s","type":"Router","definition":{
              "model":"S61SZBTPB","vendor":"SONOFF","description":"Zigbee smart switch (US version)",
              "exposes":[
                {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7,"value_on":"ON","value_off":"OFF"}]},
                {"type":"numeric","name":"power","property":"power","access":1,"unit":"W"},
                {"type":"numeric","name":"voltage","property":"voltage","access":1,"unit":"V"},
                {"type":"numeric","name":"current","property":"current","access":1,"unit":"A"},
                {"type":"numeric","name":"energy","property":"energy","access":1,"unit":"kWh"},
                {"type":"numeric","name":"linkquality","property":"linkquality","access":1}
              ]}}]
            """.formatted(friendlyName));
    }

    @Test
    void aSwitchThatMeasuresPowerIsAPowerMeterWithAnOnOffCommand() throws Exception {
        registerPowerSwitch("0xa4c1381f3884ffff");

        var descriptor = registry.descriptor("z2m-0xa4c1381f3884ffff").orElseThrow();
        assertEquals(com.cabin.orchestrator.devices.model.DeviceType.POWER_METER, descriptor.type());
        assertTrue(descriptor.capabilities().contains(com.cabin.orchestrator.devices.model.DeviceCapability.COMMAND));
        assertTrue(descriptor.capabilities().contains(com.cabin.orchestrator.devices.model.DeviceCapability.POWER_MONITOR));
    }

    @Test
    void anExplicitNullClearsTheCurrentReadingWithoutFabricatingZero() throws Exception {
        registerPowerSwitch("0xa4c1381f3884ffff");

        deliver("zigbee2mqtt/0xa4c1381f3884ffff", "{\"state\":\"ON\",\"power\":null,\"voltage\":null,\"linkquality\":148}");
        var first = registry.get("z2m-0xa4c1381f3884ffff").attributes();
        assertEquals("ON", first.get("state"));
        assertFalse(first.containsKey("power"), "an unreported power must be absent, not the text null");
        assertFalse(first.containsKey("voltage"));

        deliver("zigbee2mqtt/0xa4c1381f3884ffff", "{\"power\":412.5,\"voltage\":121.4}");
        deliver("zigbee2mqtt/0xa4c1381f3884ffff", "{\"power\":null}");
        var later = registry.get("z2m-0xa4c1381f3884ffff").attributes();
        assertNull(later.get("power"));
        assertEquals(121.4, ((Number) later.get("voltage")).doubleValue(), 0.0001);
    }

    // D16 (Reporting Topics IA), Cowork ratification 2026-09-05: before this,
    // motion/contact/leak devices never got a device_reporting_relationship
    // row for their own binary reading at all -- only numeric exposes were
    // ever captured. Fixture is the REAL exposes[] confirmed live against
    // z2m-motion_entry 2026-09-05 (via mosquitto_sub on zigbee2mqtt/bridge/devices),
    // not a simplified guess.
    @Test
    void occupancyIsCapturedAsAVendorReportedFieldAlongsideNumericOnes() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"motion_entry","type":"EndDevice","definition":{
              "model":"SNZB-03P","vendor":"SONOFF","description":"motion",
              "exposes":[
                {"type":"binary","name":"occupancy","property":"occupancy","value_on":true,"value_off":false},
                {"type":"numeric","name":"illuminance","property":"illuminance","unit":"lx"},
                {"type":"numeric","name":"battery","property":"battery","category":"diagnostic"}
              ]}}]
            """);

        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) registry.get("z2m-motion_entry").attributes().get("vendorReportedFields");
        // illuminance isn't a D7MeasurementTypes value -- excluded same as ever.
        assertEquals(List.of("occupancy", "battery"), fields);
    }

    // Fixture is the REAL exposes[] confirmed live against
    // z2m-door_front_contact 2026-09-05 -- battery_low is a real binary
    // diagnostic this device also reports, deliberately NOT in
    // BINARY_PRESENCE_FIELDS (it's not a security_presence signal).
    @Test
    void contactIsCapturedButUnrelatedBinaryDiagnosticsAreNot() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"door_front_contact","type":"EndDevice","definition":{
              "model":"SNZB-04","vendor":"SONOFF","description":"contact",
              "exposes":[
                {"type":"binary","name":"contact","property":"contact","value_on":false,"value_off":true},
                {"type":"binary","name":"battery_low","property":"battery_low","category":"diagnostic","value_on":true,"value_off":false},
                {"type":"numeric","name":"voltage","property":"voltage","category":"diagnostic"}
              ]}}]
            """);

        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) registry.get("z2m-door_front_contact").attributes().get("vendorReportedFields");
        assertEquals(List.of("contact", "voltage"), fields);
    }

    @Test
    void vendorReportedFieldsIsAbsentEntirelyWhenExposesHasNoRealMeasurements() throws Exception {
        registerDevice("motion_entry"); // fixture's own exposes:[] is empty

        assertFalse(registry.get("z2m-motion_entry").attributes().containsKey("vendorReportedFields"),
            "omitted entirely, not an empty list -- see the field's own comment on why that distinction matters");
    }

    // water_leak used to be excluded here too (binary types were entirely
    // unrecognized before D16's security_presence capture) -- updated
    // 2026-09-05 to reflect that it's now correctly captured alongside
    // temperature, same fixture, still proving composite recursion works.
    @Test
    void vendorReportedFieldsRecursesIntoCompositeFeatures() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
            [{"friendly_name":"leak_spare","type":"EndDevice","definition":{
              "model":"WL2","vendor":"THIRDREALITY","description":"leak sensor","exposes":[
                {"type":"composite","name":"leak_group","features":[
                  {"type":"numeric","name":"temperature","property":"temperature"},
                  {"type":"binary","name":"water_leak","property":"water_leak"}
                ]}
              ]}}]
            """);

        @SuppressWarnings("unchecked")
        List<String> fields = (List<String>) registry.get("z2m-leak_spare").attributes().get("vendorReportedFields");
        assertEquals(List.of("temperature", "water_leak"), fields);
    }

    @Test
    void vendorReportedFieldsArePersistedAsVendorSpecReportingRelationships() throws Exception {
        RecordingReportingRelationshipRepository reportingRepository = new RecordingReportingRelationshipRepository();
        Zigbee2MqttAdapter adapterWithPersistence = new Zigbee2MqttAdapter(
            registry, new EventPublisher(), signalQualityRegistry, reportingRepository);

        adapterWithPersistence.messageArrived("zigbee2mqtt/bridge/devices", new MqttMessage("""
            [{"friendly_name":"temp_kitchen","type":"EndDevice","definition":{
              "model":"SNZB-02WD","vendor":"SONOFF","description":"Waterproof temperature and humidity sensor",
              "exposes":[
                {"type":"numeric","name":"temperature","property":"temperature"},
                {"type":"numeric","name":"humidity","property":"humidity"}
              ]}}]
            """.getBytes()));

        List<DeviceReportingRelationship> saved = reportingRepository.findByDevice("z2m-temp_kitchen");
        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(r -> r.confirmationSource() == ConfirmationSource.VENDOR_SPEC));
        assertTrue(saved.stream().map(DeviceReportingRelationship::semanticField).toList()
            .containsAll(List.of("temperature", "humidity")));
    }

    // Presence history needs transitions, not the repeated occupancy field:
    // Z2M re-sends the whole state on every battery/linkquality report, and
    // counting TELEMETRY rows overstated real activations about 2x (91 true
    // rows for 43 real activations on Sep 15 2026). See OccupancyEdges.
    private static final class CapturingPublisher extends EventPublisher {
        final List<CabinEvent> events = new java.util.ArrayList<>();
        @Override public void publish(CabinEvent event) { events.add(event); }
        List<CabinEvent> edges() {
            return events.stream().filter(e -> e.eventType().startsWith("OCCUPANCY_SENSOR_")).toList();
        }
    }

    /** A fresh adapter that has seen bridge/devices itself -- knownFriendlyNames is per adapter instance. */
    private Zigbee2MqttAdapter adapterCapturing(CapturingPublisher publisher, OccupancyHistory history) throws Exception {
        Zigbee2MqttAdapter a = new Zigbee2MqttAdapter(registry, publisher, signalQualityRegistry,
            new DeviceReportingRelationshipRepository() {
                @Override public void upsert(DeviceReportingRelationship relationship) { }
                @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) { return List.of(); }
                @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.of(); }
            }, history);
        a.messageArrived("zigbee2mqtt/bridge/devices", new MqttMessage("""
            [{"friendly_name":"motion_entry","type":"EndDevice","definition":{
              "model":"SNZB-03PR2","description":"motion","vendor":"SONOFF","exposes":[]}}]
            """.getBytes()));
        return a;
    }

    private static void send(Zigbee2MqttAdapter a, String topic, String payload) throws Exception {
        a.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    @Test
    void occupancyFalseToTrueToFalsePublishesOneActivatedAndOneCleared() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        Zigbee2MqttAdapter a = adapterCapturing(publisher, id -> java.util.Optional.empty());

        send(a, "zigbee2mqtt/motion_entry", "{\"occupancy\": false, \"battery\": 100}");
        send(a, "zigbee2mqtt/motion_entry", "{\"occupancy\": true}");
        send(a, "zigbee2mqtt/motion_entry", "{\"occupancy\": false}");

        List<CabinEvent> edges = publisher.edges();
        assertEquals(2, edges.size(), "false->true and true->false; the opening false is not a transition");
        assertEquals(OccupancyEdges.ACTIVATED, edges.get(0).eventType());
        assertEquals(OccupancyEdges.CLEARED, edges.get(1).eventType());
        assertEquals("z2m-motion_entry", edges.get(0).sourceDeviceId());
    }

    @Test
    void aBatteryReportRepeatingOccupancyTrueIsNotANewActivation() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        Zigbee2MqttAdapter a = adapterCapturing(publisher, id -> java.util.Optional.empty());

        send(a, "zigbee2mqtt/motion_entry", "{\"occupancy\": true}");
        send(a, "zigbee2mqtt/motion_entry", "{\"battery\": 99}");              // merged state still says occupancy=true
        send(a, "zigbee2mqtt/motion_entry", "{\"linkquality\": 120}");

        assertEquals(1, publisher.edges().size());
        assertEquals(3, publisher.events.stream().filter(e -> e.eventType().equals("TELEMETRY")).count(),
            "the TELEMETRY stream is unchanged -- edges are additional events");
    }

    @Test
    void theEdgeSharesItsTimestampAndIdWithTheTelemetryRow() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        Zigbee2MqttAdapter a = adapterCapturing(publisher, id -> java.util.Optional.empty());

        send(a, "zigbee2mqtt/motion_entry", "{\"occupancy\": true}");

        CabinEvent telemetry = publisher.events.stream().filter(e -> e.eventType().equals("TELEMETRY")).findFirst().orElseThrow();
        CabinEvent edge = publisher.edges().get(0);
        assertEquals(telemetry.timestamp(), edge.timestamp(),
            "same instant, so PresenceActivityBackfill derives the identical event_id from the TELEMETRY row");
        assertEquals(OccupancyEdges.eventId("z2m-motion_entry", telemetry.timestamp(), OccupancyEdges.ACTIVATED), edge.eventId());
    }

    @Test
    void afterARestartTheStoredLastValueDecidesWhetherTrueIsANewActivation() throws Exception {

        // Stored history says the sensor was already occupied: this 'true' is a continuation, not an edge.
        CapturingPublisher wasTrue = new CapturingPublisher();
        send(adapterCapturing(wasTrue, id -> java.util.Optional.of(true)), "zigbee2mqtt/motion_entry", "{\"occupancy\": true}");
        assertTrue(wasTrue.edges().isEmpty());

        // Stored history says it was clear: the same message is a fresh activation.
        registry.update(new com.cabin.orchestrator.devices.model.DeviceStatus(
            "z2m-motion_entry", registry.get("z2m-motion_entry").type(), registry.get("z2m-motion_entry").name(),
            "ONLINE", java.time.Instant.now(), new LinkedHashMap<>(), registry.get("z2m-motion_entry").location()));
        CapturingPublisher wasFalse = new CapturingPublisher();
        send(adapterCapturing(wasFalse, id -> java.util.Optional.of(false)), "zigbee2mqtt/motion_entry", "{\"occupancy\": true}");
        assertEquals(1, wasFalse.edges().size());
    }

    @Test
    void aRetainedReplayPublishesNoEdge() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        Zigbee2MqttAdapter a = adapterCapturing(publisher, id -> java.util.Optional.empty());

        MqttMessage replay = new MqttMessage("{\"occupancy\": true}".getBytes());
        replay.setRetained(true);
        a.messageArrived("zigbee2mqtt/motion_entry", replay);

        assertTrue(publisher.events.isEmpty(), "a retained replay is the broker's cache, not something that just happened");
    }

    @Test
    void aDeviceThatDoesNotReportOccupancyNeverPublishesEdges() throws Exception {
        CapturingPublisher publisher = new CapturingPublisher();
        Zigbee2MqttAdapter a = adapterCapturing(publisher, id -> java.util.Optional.empty());

        send(a, "zigbee2mqtt/motion_entry", "{\"battery\": 100, \"temperature\": 70}");

        assertTrue(publisher.edges().isEmpty());
    }

    private static final class RecordingReportingRelationshipRepository implements DeviceReportingRelationshipRepository {
        private final Map<String, DeviceReportingRelationship> saved = new LinkedHashMap<>();

        @Override public void upsert(DeviceReportingRelationship relationship) {
            saved.put(relationship.deviceId() + "|" + relationship.semanticField(), relationship);
        }
        @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) {
            return saved.values().stream().filter(r -> r.deviceId().equals(deviceId)).toList();
        }
        @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.of(); }
    }

    // 2026-10-16: Z2M 2.14.1 answers {"value":false} (no "time") with "Invalid
    // payload", so the hub's close request never closed anything on a 2.x
    // bridge. Open and close must both carry "time", on every configured bridge.
    private List<String> permitJoinPublishes(boolean enable, int duration) throws Exception {
        adapter.configureBridgesForTest("zigbee2mqtt,home_z2m", "cabin,home");
        org.eclipse.paho.client.mqttv3.MqttClient mqtt =
            org.mockito.Mockito.mock(org.eclipse.paho.client.mqttv3.MqttClient.class);
        org.mockito.Mockito.when(mqtt.isConnected()).thenReturn(true);
        org.springframework.test.util.ReflectionTestUtils.setField(adapter, "client", mqtt);

        adapter.permitJoin(enable, duration);

        org.mockito.ArgumentCaptor<String> topics = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<MqttMessage> messages = org.mockito.ArgumentCaptor.forClass(MqttMessage.class);
        org.mockito.Mockito.verify(mqtt, org.mockito.Mockito.times(2)).publish(topics.capture(), messages.capture());
        List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < topics.getAllValues().size(); i++) {
            out.add(topics.getAllValues().get(i) + " " + new String(messages.getAllValues().get(i).getPayload()));
        }
        return out;
    }

    @Test
    void closingThePairingWindowSendsTimeZeroToEveryBridgeSoZ2mTwoAcceptsIt() throws Exception {
        List<String> sent = permitJoinPublishes(false, 0);

        assertEquals(2, sent.size());
        assertTrue(sent.contains("zigbee2mqtt/bridge/request/permit_join {\"value\":false,\"time\":0}"), sent.toString());
        assertTrue(sent.contains("home_z2m/bridge/request/permit_join {\"value\":false,\"time\":0}"), sent.toString());
    }

    @Test
    void openingThePairingWindowStillSendsTheRequestedDurationToEveryBridge() throws Exception {
        List<String> sent = permitJoinPublishes(true, 254);

        assertTrue(sent.contains("zigbee2mqtt/bridge/request/permit_join {\"value\":true,\"time\":254}"), sent.toString());
        assertTrue(sent.contains("home_z2m/bridge/request/permit_join {\"value\":true,\"time\":254}"), sent.toString());
    }

    @Test
    void smartSwitchDiscoveryCarriesSourceIdentityAndNullClearsOldPower() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"arbitrary","ieee_address":"0x1234567890abcdef",
            "definition":{"model":"3RSP02064Z","description":"Smart plug","exposes":[
              {"type":"binary","name":"state","access":3},
              {"type":"numeric","name":"power","property":"power","access":1},
              {"type":"numeric","name":"energy","property":"energy","access":1},
              {"type":"numeric","name":"power_limit","category":"config","access":3}]}}]
          """);
        var found=registry.get("z2m-arbitrary");
        assertEquals("zigbee:cabin:0x1234567890abcdef",found.attributes().get("physicalIdentity"));
        assertEquals(true,found.attributes().get("smartSwitch"));
        assertEquals(List.of("power","energy"),found.attributes().get("reportsFields"));
        assertEquals(false,found.attributes().get("enabled"));
        deliver("zigbee2mqtt/arbitrary", "{\"power\":12}");
        deliver("zigbee2mqtt/arbitrary", "{\"power\":null}");
        assertNull(registry.get("z2m-arbitrary").attributes().get("power"));
    }

    @Test
    void batteryVoltageAndWritableCalibrationDoNotMakeATemperatureSensorASwitch() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"temp_anywhere","ieee_address":"0x1234567890abcdef",
            "definition":{"description":"Temperature sensor","exposes":[
              {"type":"numeric","name":"temperature","property":"temperature","access":1},
              {"type":"numeric","name":"voltage","property":"voltage","access":1},
              {"type":"numeric","name":"temperature_calibration","property":"temperature_calibration",
               "category":"config","access":3}]}}]
          """);
        var found=registry.get("z2m-temp_anywhere");
        assertEquals(false,found.attributes().get("smartSwitch"));
        assertEquals(DeviceType.TEMPERATURE_SENSOR,found.type());
    }

    // #248 review (Cowork, 2026-10-24): capability and smart-switch evidence beyond the
    // temperature case. A config setting is not a command; battery voltage is not metering;
    // lights, locks and valve actuators carry a writable on/off but are not smart switches;
    // a multi-gang relay publishes per-endpoint state keys.

    @Test
    void aTemperatureSensorGetsNeitherCommandNorPowerMonitorFromCalibrationAndBatteryVoltage() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"temp_attic","ieee_address":"0x00124b0000000001",
            "definition":{"model":"SNZB-02P","description":"Temperature and humidity sensor","exposes":[
              {"type":"numeric","name":"temperature","property":"temperature","access":1,"unit":"C"},
              {"type":"numeric","name":"humidity","property":"humidity","access":1,"unit":"%"},
              {"type":"numeric","name":"battery","property":"battery","access":1,"category":"diagnostic"},
              {"type":"numeric","name":"voltage","property":"voltage","access":1,"unit":"mV","category":"diagnostic"},
              {"type":"numeric","name":"temperature_calibration","property":"temperature_calibration",
               "category":"config","access":3}]}}]
          """);
        var caps = registry.descriptor("z2m-temp_attic").orElseThrow().capabilities();
        assertFalse(caps.contains(DeviceCapability.COMMAND), caps.toString());
        assertFalse(caps.contains(DeviceCapability.POWER_MONITOR), caps.toString());
        assertEquals(false, registry.get("z2m-temp_attic").attributes().get("smartSwitch"));
    }

    @Test
    void voltageWithoutPowerEnergyOrCurrentIsNotPowerMonitoringEvenWithoutACategory() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"contact_back","definition":{"description":"Door sensor","exposes":[
              {"type":"binary","name":"contact","property":"contact","access":1},
              {"type":"numeric","name":"voltage","property":"voltage","access":1}]}}]
          """);
        assertFalse(registry.descriptor("z2m-contact_back").orElseThrow().capabilities()
            .contains(DeviceCapability.POWER_MONITOR));
    }

    @Test
    void aPowerLimitSettingAloneDoesNotMakeMetering() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"plain_relay","definition":{"description":"Smart switch","exposes":[
              {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7}]},
              {"type":"numeric","name":"power_outage_count","property":"power_outage_count","access":1,"category":"diagnostic"},
              {"type":"numeric","name":"power_limit","property":"power_limit","access":3,"category":"config"}]}}]
          """);
        var d = registry.descriptor("z2m-plain_relay").orElseThrow();
        assertFalse(d.capabilities().contains(DeviceCapability.POWER_MONITOR), d.capabilities().toString());
        assertTrue(d.capabilities().contains(DeviceCapability.COMMAND));
        assertEquals(true, registry.get("z2m-plain_relay").attributes().get("smartSwitch"),
            "a non-metering relay is still a smart switch");
    }

    @Test
    void aMultiGangRelayWithPerEndpointStateIsASmartSwitch() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"two_gang","definition":{"description":"Smart switch 2 gang","exposes":[
              {"type":"switch","endpoint":"l1","features":[{"type":"binary","name":"state","property":"state_l1","access":7}]},
              {"type":"switch","endpoint":"l2","features":[{"type":"binary","name":"state","property":"state_l2","access":7}]}]}}]
          """);
        assertEquals(true, registry.get("z2m-two_gang").attributes().get("smartSwitch"));
    }

    @Test
    void lightsLocksAndValveActuatorsAreNotSmartSwitches() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"bulb","definition":{"description":"Smart bulb","exposes":[
              {"type":"light","features":[{"type":"binary","name":"state","property":"state","access":7},
                {"type":"numeric","name":"brightness","property":"brightness","access":7}]}]}},
           {"friendly_name":"front_lock","definition":{"description":"Smart lock","exposes":[
              {"type":"lock","features":[{"type":"binary","name":"state","property":"state","access":7,
                "value_on":"LOCK","value_off":"UNLOCK"}]}]}},
           {"friendly_name":"main_valve","definition":{"description":"Smart water valve","exposes":[
              {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7}]}]}}]
          """);
        assertEquals(false, registry.get("z2m-bulb").attributes().get("smartSwitch"));
        assertEquals(false, registry.get("z2m-front_lock").attributes().get("smartSwitch"));
        assertEquals(false, registry.get("z2m-main_valve").attributes().get("smartSwitch"));
    }

    @Test
    void aReadOnlyStateIsNotRelayEvidence() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"status_only","definition":{"description":"Status reporter","exposes":[
              {"type":"binary","name":"state","property":"state","access":1}]}}]
          """);
        assertEquals(false, registry.get("z2m-status_only").attributes().get("smartSwitch"));
    }

    // 2026-10-24 regression after #248: the Sonoff S61 dehumidifier switch. Exposes below are
    // the real definition.exposes of S61SZBTPB read from the live bridge/devices (state, power,
    // current, voltage, linkquality; no energy). The 3RSP02064Z reference models the live
    // reference switches (power, voltage, current, energy).
    private static final String S61_FRIENDLY = "Smart Switch Zigbee | Dehumidifier";
    private static final String S61_IEEE = "0xa4c1381f3884ffff";
    private static final String S61_DEVICE = """
        {"friendly_name":"Smart Switch Zigbee | Dehumidifier","ieee_address":"0xa4c1381f3884ffff",
         "definition":{"model":"S61SZBTPB","vendor":"SONOFF",
          "description":"Smart plug: experimental state and metering adapter","exposes":[
           {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7,
             "value_on":"ON","value_off":"OFF"}]},
           {"type":"numeric","name":"power","property":"power","access":5,"unit":"W"},
           {"type":"numeric","name":"current","property":"current","access":5,"unit":"A"},
           {"type":"numeric","name":"voltage","property":"voltage","access":5,"unit":"V"},
           {"type":"numeric","name":"linkquality","property":"linkquality","access":1,"category":"diagnostic"}]}}
        """;
    private static final String THIRD_REALITY_PLUG = """
        {"friendly_name":"heater_mech_room","ieee_address":"0x1234567890abcde0",
         "definition":{"model":"3RSP02064Z","vendor":"Third Reality","description":"Zigbee / BLE smart plug with power","exposes":[
           {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7}]},
           {"type":"numeric","name":"power","property":"power","access":5,"unit":"W"},
           {"type":"numeric","name":"voltage","property":"voltage","access":5,"unit":"V"},
           {"type":"numeric","name":"current","property":"current","access":5,"unit":"A"},
           {"type":"numeric","name":"energy","property":"energy","access":5,"unit":"kWh"},
           {"type":"numeric","name":"power_drop_threshold","property":"power_drop_threshold","access":7,"category":"config"},
           {"type":"enum","name":"power_on_behavior","property":"power_on_behavior","access":7,"category":"config"}]}}
        """;

    @Test
    void theS61GetsTheSameClassAndCapabilitiesAsTheReferenceSwitch() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", "[" + S61_DEVICE + "," + THIRD_REALITY_PLUG + "]");
        var s61 = registry.descriptor("z2m-Smart_Switch_Zigbee_|_Dehumidifier").orElseThrow();
        var ref = registry.descriptor("z2m-heater_mech_room").orElseThrow();
        assertEquals(DeviceType.POWER_METER, ref.type());
        assertEquals(ref.type(), s61.type());
        assertEquals(ref.capabilities(), s61.capabilities());
        assertEquals(Set.of(DeviceCapability.COMMAND, DeviceCapability.POWER_MONITOR, DeviceCapability.TELEMETRY),
            s61.capabilities());
    }

    @Test
    void aRenamedZ2mDeviceKeepsItsAssignedRegistryIdAndReceivesItsReadings() throws Exception {
        String legacyId = "z2m-" + S61_IEEE;
        registry.registerConfiguredDevice(new DeviceDescriptor(legacyId, "Smart Switch | Dehumidifier",
            DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.POWER_MONITOR, DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/" + S61_IEEE, true, "cabin"));

        deliver("zigbee2mqtt/bridge/devices", "[" + S61_DEVICE + "]");
        deliver("zigbee2mqtt/" + S61_FRIENDLY, "{\"state\":\"ON\",\"power\":263.26,\"voltage\":122.8,\"current\":2.24,\"linkquality\":120}");

        assertNull(registry.get("z2m-Smart_Switch_Zigbee_|_Dehumidifier"),
            "a rename must not register a second row for the same physical device");
        var live = registry.get(legacyId);
        assertEquals("ON", live.attributes().get("state"));
        assertEquals(263.26, ((Number) live.attributes().get("power")).doubleValue(), 0.001);
        assertEquals("ONLINE", live.state());
        assertEquals("zigbee2mqtt/" + S61_FRIENDLY, registry.descriptor(legacyId).orElseThrow().connectionString());
    }

    @Test
    void anOnSwitchAtZeroWattsKeepsItsOnStateAndZeroPower() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", "[" + THIRD_REALITY_PLUG + "]");
        deliver("zigbee2mqtt/heater_mech_room", "{\"state\":\"ON\",\"power\":0,\"current\":0}");
        var a = registry.get("z2m-heater_mech_room").attributes();
        assertEquals("ON", a.get("state"));
        assertEquals(0, ((Number) a.get("power")).intValue());
    }

    /** Nate's rule: no Zigbee-reported parent is left generic when its exposes identify a class. */
    @Test
    void noZigbeeParentWithKnownExposesEndsAsAGenericEntity() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", "[" + S61_DEVICE + "," + THIRD_REALITY_PLUG + """
          ,{"friendly_name":"odd_temp","definition":{"description":"Environment probe","exposes":[
             {"type":"numeric","name":"temperature","property":"temperature","access":1}]}}
          ,{"friendly_name":"odd_motion","definition":{"description":"Presence thing","exposes":[
             {"type":"binary","name":"occupancy","property":"occupancy","access":1}]}}
          ,{"friendly_name":"odd_contact","definition":{"description":"Opening thing","exposes":[
             {"type":"binary","name":"contact","property":"contact","access":1}]}}
          ,{"friendly_name":"in_wall","definition":{"description":"Wall unit","exposes":[
             {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7}]}]}}
          ,{"friendly_name":"gang2","definition":{"description":"Wall unit 2","exposes":[
             {"type":"switch","endpoint":"l1","features":[{"type":"binary","name":"state","property":"state_l1","access":7}]},
             {"type":"switch","endpoint":"l2","features":[{"type":"binary","name":"state","property":"state_l2","access":7}]}]}}
          ]""");
        for (String id : List.of("z2m-Smart_Switch_Zigbee_|_Dehumidifier", "z2m-heater_mech_room", "z2m-odd_temp",
                "z2m-odd_motion", "z2m-odd_contact", "z2m-in_wall", "z2m-gang2")) {
            assertNotEquals(DeviceType.HOME_ASSISTANT_ENTITY, registry.descriptor(id).orElseThrow().type(), id);
        }
        assertEquals(DeviceType.POWER_METER, registry.descriptor("z2m-in_wall").orElseThrow().type());
        assertEquals(DeviceType.POWER_METER, registry.descriptor("z2m-gang2").orElseThrow().type());
        assertTrue(registry.descriptor("z2m-gang2").orElseThrow().capabilities().contains(DeviceCapability.COMMAND));
    }

    // Real live shapes: the Third Reality 3RWS18BZ leak sensor exposes a siren "state" switch, and
    // the main water valve is a Tuya TS0001 "1 gang switch" to Z2M. Neither is a smart switch.
    @Test
    void aLeakSensorSirenAndANamedValveActuatorAreNotSmartSwitchesOrPowerMeters() throws Exception {
        deliver("zigbee2mqtt/bridge/devices", """
          [{"friendly_name":"leak_fridge","definition":{"model":"3RWS18BZ","description":"Water sensor","exposes":[
              {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7}]},
              {"type":"binary","name":"water_leak","property":"water_leak","access":1}]}},
           {"friendly_name":"main_water_valve","definition":{"model":"TS0001","description":"1 gang switch","exposes":[
              {"type":"switch","features":[{"type":"binary","name":"state","property":"state","access":7}]}]}}]
          """);
        for (String id : List.of("z2m-leak_fridge", "z2m-main_water_valve")) {
            assertEquals(false, registry.get(id).attributes().get("smartSwitch"), id);
            assertNotEquals(DeviceType.POWER_METER, registry.descriptor(id).orElseThrow().type(), id);
        }
        assertEquals(DeviceType.WATER_LEAK_SENSOR, registry.descriptor("z2m-leak_fridge").orElseThrow().type());
    }
}
