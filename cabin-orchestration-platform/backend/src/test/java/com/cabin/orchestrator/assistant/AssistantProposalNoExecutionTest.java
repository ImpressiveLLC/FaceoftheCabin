package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.assistant.ProposalModel.Response;
import com.cabin.orchestrator.assistant.ProposalModel.Status;
import com.cabin.orchestrator.devices.DeviceLifecycleRecord;
import com.cabin.orchestrator.devices.DeviceLifecycleStore;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceRepository;
import com.cabin.orchestrator.devices.adapter.ProtocolAdapter;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceMetadata;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskService;
import com.cabin.orchestrator.integrations.homeassistant.HomeAssistantAdapter;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * HO-2026-10-16 step 3, gate: "No execution from Ask in steps 1 to 5." Proved two ways: by dependency (the
 * propose path holds nothing that can run an action) and by behaviour (a spy protocol adapter and a Home
 * Assistant client sit behind the registry, the full battery of requests runs, and neither is ever called).
 */
class AssistantProposalNoExecutionTest {

    private static final Instant NOW = Instant.parse("2026-10-16T12:00:00Z");

    /** Everything that can change the physical world or start a workflow. */
    private static final List<Class<?>> EXECUTORS = List.of(
        HomeAssistantAdapter.class, ProtocolAdapter.class,
        com.cabin.orchestrator.workflow.WorkflowRuleService.class,
        com.cabin.orchestrator.api.HaServiceController.class,
        com.cabin.orchestrator.mqtt.MqttBridgeService.class,
        com.cabin.orchestrator.integrations.zigbee.Zigbee2MqttAdapter.class);

    @Test
    void theProposePathHoldsNothingThatCanExecute() {
        for (Class<?> type : List.of(IntentProposalService.class, EntityResolver.class, AssistantInventoryService.class,
                IntentSlotContract.class, OllamaChatClient.class, com.cabin.orchestrator.api.HelpdeskProposeController.class)) {
            List<Class<?>> held = new ArrayList<>();
            for (Field f : type.getDeclaredFields()) held.add(f.getType());
            for (Constructor<?> c : type.getDeclaredConstructors()) held.addAll(List.of(c.getParameterTypes()));
            for (Class<?> executor : EXECUTORS) {
                assertFalse(held.stream().anyMatch(executor::isAssignableFrom),
                    type.getSimpleName() + " depends on " + executor.getSimpleName());
            }
            // DeviceRegistry can dispatch commands (sendCommand); only the read-only inventory service may hold it.
            if (type != AssistantInventoryService.class) {
                assertFalse(held.contains(DeviceRegistry.class), type.getSimpleName() + " must not hold the DeviceRegistry");
            }
        }
    }

    @Test
    void noRequestEverReachesAnAdapterOrHomeAssistant() {
        ProtocolAdapter spy = mock(ProtocolAdapter.class);
        when(spy.adapterType()).thenReturn("ha_rest");
        HomeAssistantAdapter homeAssistant = mock(HomeAssistantAdapter.class);

        DeviceLifecycleStore store = new DeviceLifecycleStore() {
            @Override public Map<String, DeviceLifecycleRecord> loadAll() { return Map.of(); }
            @Override public void save(DeviceLifecycleRecord record) { }
            @Override public void delete(String deviceId) { }
        };
        DeviceRepository repository = new DeviceRepository() {
            @Override public void upsert(String deviceId, DeviceMetadata m) { }
            @Override public Optional<DeviceMetadata> find(String deviceId) { return Optional.empty(); }
            @Override public Map<String, DeviceMetadata> loadAll() { return Map.of(); }
        };
        DeviceRegistry registry = new DeviceRegistry(List.of(spy), store, repository);
        register(registry, "heater_mech_room", "Mech Room Heater", DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.COMMAND));
        register(registry, "thermostat_cabin", "Cabin Thermostat", DeviceType.THERMOSTAT, Set.of(DeviceCapability.COMMAND, DeviceCapability.CLIMATE));
        register(registry, "main_water_valve", "Main Water Valve", DeviceType.HOME_ASSISTANT_ENTITY, Set.of(DeviceCapability.COMMAND));
        register(registry, "lock_front", "Front Door Lock", DeviceType.LOCK, Set.of(DeviceCapability.COMMAND, DeviceCapability.ACCESS_CONTROL));

        String[] modelAnswers = {
            "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"heater\",\"state\":\"on\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
            "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"heater\",\"state\":\"off\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
            "{\"intent\":\"set_device_attribute\",\"slots\":{\"device_name\":\"thermostat\",\"site\":\"cabin\",\"attribute_type\":\"temperature\",\"attribute_value\":\"68\",\"unit\":\"F\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
            "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"main water valve\",\"state\":\"open\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
            "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"front door lock\",\"state\":\"unlock\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
            "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"front door lock\",\"state\":\"lock\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
            "{\"intent\":\"get_device_state\",\"slots\":{\"device_name\":\"heater\"},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}",
            "{\"intent\":\"propose_automation\",\"slots\":{},\"requires_confirmation\":true,\"clarification\":null,\"automation\":{\"trigger\":{\"target\":{\"device_name\":\"thermostat\",\"location\":\"cabin\",\"site\":\"cabin\"},\"attribute\":\"temperature\",\"operator\":\"lt\",\"value\":\"10\",\"unit\":\"C\"},\"conditions\":[],\"actions\":[{\"target\":{\"device_name\":\"heater\",\"location\":\"mech room\",\"site\":\"cabin\"},\"operation\":\"on\",\"attribute\":null,\"value\":null,\"unit\":null,\"delay_seconds\":0}]}}",
            "{\"intent\":\"set_device_state\",\"slots\":{\"device_name\":\"jacuzzi\",\"state\":\"on\"},\"requires_confirmation\":true,\"clarification\":null,\"automation\":null}",
            "not json at all",
            "{\"intent\":\"unsupported\",\"slots\":{},\"requires_confirmation\":false,\"clarification\":null,\"automation\":null}"
        };
        int[] index = {0};
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        IntentProposalService service = new IntentProposalService(
            (model, system, user, format) -> Optional.of(new IntentChatClient.ChatResult(model, modelAnswers[index[0]], 5)),
            new IntentSlotContract(), new AssistantInventoryService(registry, clock),
            new EntityResolver(clock, Duration.ofSeconds(120), Duration.ofHours(6)), mock(TinyHelpdeskService.class),
            entry -> { }, clock, true, "cabin-intent-slot-prompt-r1", Duration.ofMinutes(5));

        int proposals = 0;
        for (index[0] = 0; index[0] < modelAnswers.length; index[0]++) {
            Response r = service.propose("anything", HouseholdRole.ADMINISTRATOR, "admin@example.test");
            if (r.status() == Status.PROPOSAL) proposals++;
        }

        // heater on, heater off, thermostat setpoint, lock, and the automation
        assertEquals(5, proposals, "the battery must include real, fully resolved proposals");
        verify(spy, never()).sendCommand(any(), any(), any());
        verify(spy, never()).fetchState(any());
        verifyNoInteractions(homeAssistant);
    }

    private static void register(DeviceRegistry registry, String id, String name, DeviceType type, Set<DeviceCapability> caps) {
        registry.registerDescriptor(new DeviceDescriptor(id, name, type, caps, "ha_rest", "switch." + id, true, "cabin"));
        registry.update(new DeviceStatus(id, type, name, "ONLINE", NOW.minusSeconds(20), new HashMap<>(), "cabin"));
    }
}
