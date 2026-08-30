package com.cabin.orchestrator.integrations.zigbee;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceType;
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

    @Test
    void anUnregisteredDevicesStateMessageIsIgnoredEntirely() throws Exception {
        // No registerDevice() call -- messageArrived's own routing requires
        // the friendly name to already be known (bridge/devices), matching
        // real Z2M behavior where bridge/devices always arrives first.
        deliver("zigbee2mqtt/never_registered", "{\"linkquality\": 160}");

        assertTrue(signalQualityRegistry.assess("z2m-never_registered").isEmpty());
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
    // reported measurement) and voltage/linkquality (real diagnostic values,
    // but not environmental measurement types this schema models).
    @Test
    void vendorReportedFieldsExcludesConfigAndDiagnosticExposesKeepingOnlyRealMeasurements() throws Exception {
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
        // Order follows the exposes[] array itself (battery appears before
        // temperature/humidity in the real fixture) -- this asserts the
        // filtering, not a re-sort the method doesn't claim to do.
        assertEquals(List.of("battery", "temperature", "humidity"), fields);
    }

    @Test
    void vendorReportedFieldsIsAbsentEntirelyWhenExposesHasNoRealMeasurements() throws Exception {
        registerDevice("motion_entry"); // fixture's own exposes:[] is empty

        assertFalse(registry.get("z2m-motion_entry").attributes().containsKey("vendorReportedFields"),
            "omitted entirely, not an empty list -- see the field's own comment on why that distinction matters");
    }

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
        assertEquals(List.of("temperature"), fields);
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
}
