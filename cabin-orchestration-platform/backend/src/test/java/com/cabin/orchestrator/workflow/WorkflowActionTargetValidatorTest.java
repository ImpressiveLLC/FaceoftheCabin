package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct coverage of the validator itself -- RulesControllerTest only
 * exercises it indirectly (as a pass/fail gate on create/activate), so it
 * never actually proves each individual rejection reason fires for the
 * right input. Real Postgres via JdbcWorkflowVocabularyStore, same
 * Testcontainers pattern as WorkflowRuleServiceTest/RulesControllerTest --
 * loadSupportedActions() runs real SQL, not something fakeable here.
 */
@Testcontainers
class WorkflowActionTargetValidatorTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private DeviceRegistry deviceRegistry;
    private WorkflowActionTargetValidator validator;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        deviceRegistry = new DeviceRegistry(List.of());
        JdbcWorkflowVocabularyStore vocabularyStore = new JdbcWorkflowVocabularyStore(jdbc);
        validator = new WorkflowActionTargetValidator(deviceRegistry, vocabularyStore);
    }

    private WorkflowAction action(String actionDefinitionId, String targetDeviceId) {
        return new WorkflowAction("a1", "wf-1", 0, actionDefinitionId, targetDeviceId, Map.of(), null);
    }

    /** Mirrors DeviceDiscoveryController.applyNew()'s CANDIDATE -> ACCEPT -> saveConfiguration flow. */
    private void registerAssignedDevice(String deviceId, Set<DeviceCapability> capabilities) {
        deviceRegistry.registerCandidate(new DeviceDescriptor(
            deviceId, "Test Device", DeviceType.HOME_ASSISTANT_ENTITY, capabilities,
            "mqtt", "zigbee2mqtt/" + deviceId, true, "cabin"), Map.of());
        deviceRegistry.applyLifecycleAction(deviceId, DeviceLifecycleAction.ACCEPT);
        deviceRegistry.saveConfiguration(deviceId, "Test Device", true);
    }

    @Test
    void aValidCommandCapableAssignedTargetPasses() {
        registerAssignedDevice("z2m-main_water_valve", Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY));

        assertNull(validator.validate(List.of(action("action_main_water_valve_off", "z2m-main_water_valve"))));
    }

    @Test
    void actionsThatDoNotNeedATargetAreNeverChecked() {
        // notify_critical/log_event have needsTarget=false in the seeded
        // vocabulary -- a null targetDeviceId must not be flagged.
        assertNull(validator.validate(List.of(action("notify_critical", null))));
        assertNull(validator.validate(List.of(action("log_event", null))));
    }

    @Test
    void anUnrecognizedActionDefinitionIdIsNotThisValidatorsJob() {
        // WorkflowRuleService.executeAction() is the real enforcement point
        // for "does this action id do anything" -- an unknown id has no
        // vocabulary entry, so there's nothing for this validator to check.
        assertNull(validator.validate(List.of(action("action_totally_made_up", null))));
    }

    @Test
    void aMissingTargetOnATargetNeedingActionIsRejected() {
        String violation = validator.validate(List.of(action("action_main_water_valve_off", null)));

        assertNotNull(violation);
        assertTrue(violation.contains("requires a target device"));
    }

    @Test
    void aBlankTargetIsTreatedTheSameAsMissing() {
        String violation = validator.validate(List.of(action("action_main_water_valve_off", "   ")));

        assertNotNull(violation);
        assertTrue(violation.contains("requires a target device"));
    }

    @Test
    void aNonexistentTargetDeviceIsRejected() {
        String violation = validator.validate(List.of(action("action_main_water_valve_off", "z2m-does_not_exist")));

        assertNotNull(violation);
        assertTrue(violation.contains("does not exist"));
    }

    @Test
    void aTargetMissingTheRequiredCapabilityIsRejected() {
        // Registered without COMMAND -- action_main_water_valve_off requires it.
        registerAssignedDevice("z2m-sensor_only", Set.of(DeviceCapability.TELEMETRY));

        String violation = validator.validate(List.of(action("action_main_water_valve_off", "z2m-sensor_only")));

        assertNotNull(violation);
        assertTrue(violation.contains("does not have the COMMAND capability"));
    }

    @Test
    void aTargetThatIsOnlyACandidateNotYetAssignedIsRejected() {
        // registerCandidate() alone leaves the device CANDIDATE --
        // allowsActiveUse() is only true once ASSIGNED, matching
        // DeviceRegistry.sendCommand()'s own real dispatch-time gate.
        deviceRegistry.registerCandidate(new DeviceDescriptor(
            "z2m-not_assigned_yet", "Test Device", DeviceType.HOME_ASSISTANT_ENTITY,
            Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY),
            "mqtt", "zigbee2mqtt/z2m-not_assigned_yet", true, "cabin"), Map.of());

        String violation = validator.validate(List.of(action("action_main_water_valve_off", "z2m-not_assigned_yet")));

        assertNotNull(violation);
        assertTrue(violation.contains("is not assigned/active"));
    }

    @Test
    void aTargetThatWasIgnoredIsRejected() {
        registerAssignedDevice("z2m-ignored_device", Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY));
        deviceRegistry.applyLifecycleAction("z2m-ignored_device", DeviceLifecycleAction.IGNORE);

        String violation = validator.validate(List.of(action("action_main_water_valve_off", "z2m-ignored_device")));

        assertNotNull(violation);
        assertTrue(violation.contains("is not assigned/active"));
    }

    @Test
    void theFirstViolationInAMultiActionListIsReturnedNotTheLast() {
        registerAssignedDevice("z2m-main_water_valve", Set.of(DeviceCapability.COMMAND, DeviceCapability.TELEMETRY));

        String violation = validator.validate(List.of(
            action("notify_critical", null),
            action("action_main_water_valve_off", "z2m-does_not_exist"),
            action("action_main_water_valve_open", "z2m-also_missing")));

        assertNotNull(violation);
        assertTrue(violation.contains("z2m-does_not_exist"));
    }

    @Test
    void anEmptyActionListPasses() {
        assertNull(validator.validate(List.of()));
    }
}
