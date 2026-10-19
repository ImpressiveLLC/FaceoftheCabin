package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.api.AssistantInventoryController;
import com.cabin.orchestrator.devices.DeviceLifecycleStore;
import com.cabin.orchestrator.devices.DeviceLifecycleRecord;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceRepository;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** W-67 slice 1: scope, role filter, version stability and, above all, that no secret is ever exported. */
class AssistantInventoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Map<String, DeviceMetadata> metadata = new HashMap<>();
    private DeviceRegistry registry;
    private AssistantInventoryService service;

    @BeforeEach
    void setUp() {
        DeviceRepository repository = new DeviceRepository() {
            @Override public void upsert(String deviceId, DeviceMetadata m) { metadata.put(deviceId, m); }
            @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.ofNullable(metadata.get(deviceId)); }
            @Override public Map<String, DeviceMetadata> loadAll() { return metadata; }
        };
        DeviceLifecycleStore store = new DeviceLifecycleStore() {
            @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.of(); }
            @Override public void save(DeviceLifecycleRecord record) { }
            @Override public void delete(String deviceId) { }
        };
        registry = new DeviceRegistry(List.of(), store, repository);
        service = new AssistantInventoryService(registry, Clock.fixed(NOW, ZoneOffset.UTC));

        // A heater whose connection string and attribute bag are full of things that must never leave the server.
        registry.registerDescriptor(new DeviceDescriptor("heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.POWER_MONITOR), "ha_rest",
            "switch.heater_mech_room", true, "cabin"));
        registry.update(status("heater_mech_room", DeviceType.HOME_ASSISTANT_ENTITY, "Mech Room Heater", "ONLINE", "cabin", Map.of(
            "room", "Mechanical room",
            "ip_address", "192.168.2.77",
            "mac", "aa:bb:cc:dd:ee:ff",
            "mqtt_topic", "zigbee2mqtt/heater_mech_room/set",
            "ha_token", "SECRETTOKENVALUE",
            "password", "hunter2",
            "url", "http://cabin-hub:8123/api/states/switch.heater_mech_room")));
        registry.registerDescriptor(new DeviceDescriptor("lock_front", "Front Door Lock", DeviceType.LOCK,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL), "mqtt",
            "zigbee2mqtt/lock_front", true, "cabin"));
        registry.registerDescriptor(new DeviceDescriptor("cam_outside", "Outside Camera", DeviceType.CAMERA,
            Set.of(DeviceCapability.STREAM), "rtsp",
            "rtsp://admin:camerapass@192.168.2.20:554/stream", true, "cabin"));
        registry.registerDescriptor(new DeviceDescriptor("temp_kitchen", "Kitchen Temperature", DeviceType.TEMPERATURE_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "zigbee2mqtt/temp_kitchen", true, "cabin"));
        registry.update(status("temp_kitchen", DeviceType.TEMPERATURE_SENSOR, "Kitchen Temperature", "OFFLINE", "cabin", Map.of()));
        metadata.put("temp_kitchen", new DeviceMetadata(null, null, "Kitchen", null, null, null, null, null, 1));
    }

    private static DeviceStatus status(String id, DeviceType type, String name, String state, String site, Map<String, Object> attrs) {
        return new DeviceStatus(id, type, name, state, NOW.minusSeconds(30), new HashMap<>(attrs), site);
    }

    private static List<String> ids(AssistantInventory inventory) {
        return inventory.entries().stream().map(AssistantInventory.Entry::entityId).toList();
    }

    private AssistantInventory.Entry entry(AssistantInventory inventory, String id) {
        return inventory.entries().stream().filter(e -> e.entityId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void anAdministratorSeesEveryInScopeDeviceSortedByEntityId() {
        AssistantInventory inventory = service.snapshot(HouseholdRole.ADMINISTRATOR);

        assertEquals(NOW, inventory.generatedAt());
        assertTrue(ids(inventory).containsAll(List.of("cam_outside", "heater_mech_room", "lock_front", "temp_kitchen")));
        assertEquals(ids(inventory).stream().sorted().toList(), ids(inventory));
    }

    @Test
    void candidatesAndDeferredOrIgnoredDevicesAreNotTargets() {
        registry.registerCandidate(new DeviceDescriptor("z2m-new_thing", "New Thing", DeviceType.MOTION_SENSOR,
            Set.of(DeviceCapability.PRESENCE), "mqtt", "zigbee2mqtt/new_thing", false, "cabin"), Map.of());
        registry.applyLifecycleAction("temp_kitchen", DeviceLifecycleAction.IGNORE);

        List<String> visible = ids(service.snapshot(HouseholdRole.ADMINISTRATOR));

        assertFalse(visible.contains("z2m-new_thing"));
        assertFalse(visible.contains("temp_kitchen"));
        assertTrue(visible.contains("heater_mech_room"));
    }

    @Test
    void anAdultMemberDoesNotSeeLocksOrCameras() {
        List<String> visible = ids(service.snapshot(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));

        assertFalse(visible.contains("lock_front"));
        assertFalse(visible.contains("cam_outside"));
        assertTrue(visible.contains("heater_mech_room"));
        assertTrue(visible.contains("temp_kitchen"));
    }

    @Test
    void everyOtherRoleAndAnAbsentRoleGetsAnEmptyInventory() {
        for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.CHILD, HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null}) {
            assertTrue(service.snapshot(role).entries().isEmpty(), "role " + role);
        }
    }

    @Test
    void entriesCarryIdentitySiteAreaCapabilitiesAndAvailability() {
        AssistantInventory inventory = service.snapshot(HouseholdRole.ADMINISTRATOR);

        AssistantInventory.Entry heater = entry(inventory, "heater_mech_room");
        assertEquals("Mech Room Heater", heater.name());
        assertEquals("cabin", heater.site());
        assertEquals("Mechanical room", heater.room());
        assertEquals(List.of("COMMAND", "POWER_MONITOR"), heater.capabilities());
        assertEquals("ONLINE", heater.availability());
        assertEquals("ASSIGNED", heater.lifecycle());
        assertTrue(heater.enabled());
        assertEquals(NOW.minusSeconds(30), heater.lastObserved());

        AssistantInventory.Entry kitchen = entry(inventory, "temp_kitchen");
        assertEquals("Kitchen", kitchen.area());
        assertEquals("TEMPERATURE_SENSOR", kitchen.deviceClass());
        assertEquals("CLIMATE", kitchen.category());
        assertEquals("OFFLINE", kitchen.availability());
    }

    @Test
    void aDisabledDeviceIsListedAsDisabledNotHidden() {
        AssistantInventory.Entry homeCamera = entry(service.snapshot(HouseholdRole.ADMINISTRATOR), "home-cam-front");

        assertFalse(homeCamera.enabled());
        assertEquals("home", homeCamera.site());
    }

    @Test
    void noCredentialTokenAddressTopicOrUrlIsEverExported() throws Exception {
        // The registry also holds the disabled home camera seeds, whose rtsp URLs embed a password placeholder and a LAN address.
        String json = JSON.writeValueAsString(service.snapshot(HouseholdRole.ADMINISTRATOR));

        // ":8123" and not "8123": the JSON carries wall-clock timestamps whose digits can contain "8123" by chance (flaked the deploy gate 2026-10-19).
        for (String forbidden : List.of("192.168", "aa:bb:cc:dd:ee:ff", "SECRETTOKENVALUE", "hunter2", "camerapass",
                "FRIGATE_RTSP_PASSWORD", "rtsp", "http://", "https://", "://", "zigbee2mqtt/", "/set", "cabin-hub", ":8123",
                "ip_address", "mqtt_topic", "ha_token", "password", "connectionString", "switch.heater_mech_room")) {
            assertFalse(json.contains(forbidden), "inventory leaked '" + forbidden + "': " + json);
        }
        assertFalse(Pattern.compile("\\b\\d{1,3}(\\.\\d{1,3}){3}\\b").matcher(json).find(), "an IPv4 address leaked");
        assertFalse(Pattern.compile("(?i)\\b[0-9a-f]{2}(:[0-9a-f]{2}){5}\\b").matcher(json).find(), "a MAC address leaked");
    }

    @Test
    void theVersionIgnoresObservationTimeButChangesWithAvailabilityAndCapabilities() {
        String before = service.snapshot(HouseholdRole.ADMINISTRATOR).version();

        // A newer reading with the same state changes nothing a plan depends on.
        registry.update(new DeviceStatus("heater_mech_room", DeviceType.HOME_ASSISTANT_ENTITY, "Mech Room Heater", "ONLINE",
            NOW.minusSeconds(1), new HashMap<>(Map.of("room", "Mechanical room")), "cabin"));
        assertEquals(before, service.snapshot(HouseholdRole.ADMINISTRATOR).version());

        registry.update(new DeviceStatus("heater_mech_room", DeviceType.HOME_ASSISTANT_ENTITY, "Mech Room Heater", "OFFLINE",
            NOW, new HashMap<>(Map.of("room", "Mechanical room")), "cabin"));
        String offline = service.snapshot(HouseholdRole.ADMINISTRATOR).version();
        assertNotEquals(before, offline);

        registry.registerDescriptor(new DeviceDescriptor("heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND), "ha_rest", "switch.heater_mech_room", true, "cabin"));
        assertNotEquals(offline, service.snapshot(HouseholdRole.ADMINISTRATOR).version());
    }

    @Test
    void theVersionIsDeterministicAndPerRole() {
        assertEquals(service.snapshot(HouseholdRole.ADMINISTRATOR).version(), service.snapshot(HouseholdRole.ADMINISTRATOR).version());
        assertNotEquals(service.snapshot(HouseholdRole.ADMINISTRATOR).version(), service.snapshot(HouseholdRole.ADULT_HOUSEHOLD_MEMBER).version());
    }

    @Test
    void theRouteRefusesAnyoneButAdministratorsAndAdults() {
        AssistantInventoryController controller = new AssistantInventoryController(service);

        assertEquals(403, controller.inventory(requestWithRole(null)).getStatusCode().value());
        assertEquals(403, controller.inventory(requestWithRole(HouseholdRole.CHILD)).getStatusCode().value());
        assertEquals(403, controller.inventory(requestWithRole(HouseholdRole.KIOSK_DISPLAY)).getStatusCode().value());

        ResponseEntity<?> admin = controller.inventory(requestWithRole(HouseholdRole.ADMINISTRATOR));
        assertEquals(200, admin.getStatusCode().value());
        assertInstanceOf(AssistantInventory.class, admin.getBody());
        ResponseEntity<?> adult = controller.inventory(requestWithRole(HouseholdRole.ADULT_HOUSEHOLD_MEMBER));
        assertEquals(200, adult.getStatusCode().value());
        assertFalse(ids((AssistantInventory) adult.getBody()).contains("lock_front"));
    }

    private static MockHttpServletRequest requestWithRole(HouseholdRole role) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/assistant/inventory");
        if (role != null) request.setAttribute(GoogleAuthInterceptor.REQUEST_ATTR_HOUSEHOLD_ROLE, role);
        return request;
    }
}
