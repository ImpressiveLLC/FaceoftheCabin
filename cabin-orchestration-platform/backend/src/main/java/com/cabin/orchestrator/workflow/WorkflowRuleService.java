package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import com.cabin.orchestrator.workflow.model.WorkflowExecution;
import com.cabin.orchestrator.workflow.model.WorkflowRule;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * The human-configured workflow interpreter. Reads workflow_rule rows (via
 * JdbcWorkflowRuleStore) and matches them against every incoming CabinEvent
 * -- the same event AutomationRuleService already evaluates, hooked in
 * right after it in EventConsumer.pollLoop(), not a parallel pipeline.
 *
 * Compound (ordered actions on one workflow_rule) vs. parallel/layered (a
 * second, independent workflow_rule sharing the same trigger, e.g. a human-
 * added "reopen valve" workflow) are both just "iterate every matching row"
 * here -- neither blocks or depends on the other succeeding. A failed
 * action never skips the rest of its own workflow's action list; each
 * result is recorded independently in the execution's actionResults.
 *
 * Hardened 2026-08-14 after an external review of the first live test found
 * three real gaps, all fixed here:
 * 1. Idempotency -- a Kafka redelivery of the same source event must not
 *    re-fire a physical action. Guarded two ways: a check-before-execute
 *    against JdbcWorkflowExecutionStore.existsForTriggerEvent() (so a
 *    duplicate is caught BEFORE any command is sent, not just before the
 *    save), plus a DB-level unique index as defense in depth.
 * 2. Edge detection -- a leak sensor re-reporting the same water_leak:true
 *    (routine heartbeat, not a new event) must not re-fire a workflow
 *    that's already active. Guarded via findActive(): a workflow with an
 *    uncleared execution is skipped until it clears.
 * 3. Command confirmation -- sendCommand() returning true only proves the
 *    MQTT publish succeeded, not that the physical device moved (found
 *    live: a competing HA automation silently reverted a reopen command
 *    within the same second). Command-kind actions now go ACCEPTED ->
 *    (scheduled re-check) -> CONFIRMED or UNCONFIRMED, matching the
 *    confirmation pattern CabinAutomations#1 already uses for the same
 *    device. UNCONFIRMED publishes its own CRITICAL notification.
 *
 * v1 (2026-08-14): trigger matching is a small hardcoded lookup table from
 * CabinEvent shape -> trigger_definition id, matching exactly the flagship
 * trigger_definition entities in docs/ontology.yaml. A real ontology-driven
 * vocabulary lookup (via OntologyLookupService) replaces
 * resolveTriggerDefinitionId()/resolveClearedTriggerDefinitionId() once
 * that vocabulary exists -- tracked as real follow-up, not silently
 * permanent.
 *
 * Expanded 2026-08-21 (user report: only water-leak/camera existed as real
 * trigger options despite far more already flowing) from that single
 * hardcoded water_leak check into FIELD_TRIGGERS below, a declarative table
 * of every TELEMETRY/SECURITY_ARMED_CHANGED/PRESENCE_CHANGED payload-field
 * trigger this engine now understands -- CLEARED_TRIGGER_IDS and
 * STATE_TRIGGERS_WITH_CLEAR_SIGNAL both derive from it now instead of being
 * hand-kept in sync separately. Every new field trigger's real payload
 * field name is confirmed against a live-verified docs/ontology.yaml device
 * entry (e.g. zigbee_door_front_contact's own documented `contact` field),
 * not guessed. resolveTriggerDefinitionId()/resolveClearedTriggerDefinitionId()
 * became resolveTriggerDefinitionIds()/resolveClearedTriggerDefinitionIds()
 * (plural) in the same pass -- a real bug fix, not just refactor
 * convenience: Zigbee2MqttAdapter.handleDeviceState() merges each new
 * message onto the device's *entire existing* attribute map before
 * publishing, so one TELEMETRY event can legitimately carry more than one
 * already-true condition at once (e.g. a device mid water-leak that also
 * just tripped tamper) -- the old single-String-return version would have
 * silently fired only the first match.
 */
@Service
public class WorkflowRuleService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRuleService.class);

    // Milliseconds, not seconds -- lets tests override these to tens of
    // milliseconds via ReflectionTestUtils (same @Value-outside-Spring
    // pattern AutomationRuleServiceTest already uses) instead of a test
    // suite actually waiting 10-30 real seconds per confirmation check.
    @Value("${cabin.workflow.confirmation.initialDelayMillis:10000}")
    private long confirmationInitialDelayMillis;

    @Value("${cabin.workflow.confirmation.finalDelayMillis:30000}")
    private long confirmationFinalDelayMillis;

    private final JdbcWorkflowRuleStore ruleStore;
    private final JdbcWorkflowExecutionStore executionStore;
    private final DeviceRegistry deviceRegistry;
    private final CommandCatalogService commandCatalog;
    private final EventPublisher eventPublisher;
    private final ScheduledExecutorService confirmationScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "workflow-command-confirm");
        t.setDaemon(true);
        return t;
    });

    public WorkflowRuleService(JdbcWorkflowRuleStore ruleStore, JdbcWorkflowExecutionStore executionStore,
                                DeviceRegistry deviceRegistry, CommandCatalogService commandCatalog,
                                EventPublisher eventPublisher) {
        this.ruleStore = ruleStore;
        this.executionStore = executionStore;
        this.deviceRegistry = deviceRegistry;
        this.commandCatalog = commandCatalog;
        this.eventPublisher = eventPublisher;
    }

    /**
     * One TELEMETRY/SECURITY_ARMED_CHANGED/PRESENCE_CHANGED payload field
     * this engine treats as a state+clear-signal trigger pair. Both
     * directions are independently matchable/selectable triggers (e.g. a
     * workflow can react to either "water leak detected" or "water leak
     * cleared") -- see class doc for why this table exists and the real
     * multi-match bug fixing it closed. Every payloadField/detectedValue/
     * clearedValue here is a real, live-verified device field, not a guess
     * -- see each new trigger's own docs/ontology.yaml entry for the
     * exact citation.
     */
    private record FieldTrigger(String detectedId, String clearedId, String eventType, String payloadField,
                                 Object detectedValue, Object clearedValue,
                                 String detectedDescription, String clearedDescription) {}

    private static final List<FieldTrigger> FIELD_TRIGGERS = List.of(
        new FieldTrigger("trigger_water_leak_detected", "trigger_water_leak_cleared", "TELEMETRY",
            "water_leak", true, false, "Water leak detected", "Water leak cleared"),
        // contact:false = open -- see zigbee_door_front_contact's own
        // docs/ontology.yaml definition ("Reports contact (boolean, false = open)").
        new FieldTrigger("trigger_door_contact_opened", "trigger_door_contact_closed", "TELEMETRY",
            "contact", false, true, "Door contact opened", "Door contact closed"),
        new FieldTrigger("trigger_motion_detected", "trigger_motion_cleared", "TELEMETRY",
            "occupancy", true, false, "Motion detected", "Motion cleared"),
        new FieldTrigger("trigger_tamper_detected", "trigger_tamper_cleared", "TELEMETRY",
            "tamper", true, false, "Tamper detected", "Tamper cleared"),
        new FieldTrigger("trigger_battery_low_detected", "trigger_battery_low_cleared", "TELEMETRY",
            "battery_low", true, false, "Battery low", "Battery no longer low"),
        // zigbee_heater_mech_room/zigbee_smart_switch_breaker_box's own
        // docs/ontology.yaml entries both document "state (on/off)".
        new FieldTrigger("trigger_plug_turned_on", "trigger_plug_turned_off", "TELEMETRY",
            "state", "ON", "OFF", "Device turned on", "Device turned off"),
        // Bridged in by E4 below (MqttBridgeService.handleArmedTopic()) --
        // previously updated SecurityStateRegistry only, never published.
        new FieldTrigger("trigger_security_armed", "trigger_security_disarmed", "SECURITY_ARMED_CHANGED",
            "armed", true, false, "Armed away", "Disarmed"),
        // Bridged in by E4 below (MqttBridgeService.handlePresenceTopic()) --
        // description is dynamic (per-person), handled in
        // describeTriggeringEvent() rather than these static strings.
        new FieldTrigger("trigger_presence_arrived", "trigger_presence_departed", "PRESENCE_CHANGED",
            "present", true, false, null, null),
        // Added 2026-08-21 -- MqttBridgeService.handleKiddeCoAlarmTopic(),
        // fed by the new cabin_security_publish_kidde_co_alarm HA
        // automation (same allow-listed shell_command pattern as
        // armed/presence). Was docs/ontology.yaml's trigger_kidde_co_alarm
        // candidate until this shipped.
        new FieldTrigger("trigger_kidde_co_alarm", "trigger_kidde_co_alarm_cleared", "KIDDE_CO_ALARM_CHANGED",
            "alarm", true, false, "Kidde CO alarm active", "Kidde CO alarm cleared")
    );

    private static final Map<String, String> CLEARED_TRIGGER_IDS = buildClearedTriggerIds();
    private static final Set<String> STATE_TRIGGERS_WITH_CLEAR_SIGNAL = CLEARED_TRIGGER_IDS.keySet();

    /**
     * CLEARED_TRIGGER_IDS/STATE_TRIGGERS_WITH_CLEAR_SIGNAL both derive from
     * FIELD_TRIGGERS plus two manual entries for the pairs that aren't a
     * simple payload-field equality match (E2's freeze-risk numeric
     * threshold, E3's Blink MOTION_ON/MOTION_OFF eventType pair) -- one row
     * to add a new state+clear trigger, not three places to keep in sync
     * by hand the way the original single water-leak check was.
     */
    private static Map<String, String> buildClearedTriggerIds() {
        Map<String, String> ids = FIELD_TRIGGERS.stream()
            .collect(Collectors.toMap(FieldTrigger::detectedId, FieldTrigger::clearedId, (a, b) -> a, LinkedHashMap::new));
        ids.put("trigger_blink_motion_detected", "trigger_blink_motion_cleared");
        ids.put("trigger_freeze_risk_detected", "trigger_freeze_risk_cleared");
        ids.put("trigger_mold_risk_detected", "trigger_mold_risk_cleared");
        return Map.copyOf(ids);
    }

    // E2 -- freeze-risk numeric threshold. A 4°F hysteresis band (not a
    // single 32.0°F boundary both directions) avoids rapid detected/cleared
    // flapping right at freezing -- the same reasoning AutomationRuleService's
    // existing separate 38°F threshold implies but doesn't itself need
    // (it has no clear-signal concept to flap).
    private static final double FREEZE_RISK_THRESHOLD_F = 32.0;
    private static final double FREEZE_RISK_CLEAR_THRESHOLD_F = 36.0;

    // Mold risk, mirroring freeze-risk's own shape exactly (E2-style numeric
    // threshold, not a FIELD_TRIGGERS equality match) -- 60% is the user's
    // own explicitly-cited EPA mold-risk threshold, not the older, looser
    // ">75% sustained" note docs/ontology.yaml's humidity_percent entity had
    // carried since an earlier session; that entity's own note was updated
    // to match in the same commit as this. A 2-point hysteresis band (clear
    // at <58%, not <60%) avoids rapid detected/cleared flapping right at
    // the boundary, same reasoning as freeze-risk's 4°F band -- kept
    // narrower here since humidity naturally drifts more slowly than a
    // freeze event, so a tight band is less likely to flap in practice.
    // Applies to ANY device whose payload carries a `humidity` field --
    // both native Zigbee sensors and HA-discovered ones normalize into
    // this same key (HomeAssistantDiscoveryService.semanticFieldFor()),
    // so this one check covers Kidde and every Sonoff sensor uniformly
    // with no per-device wiring.
    private static final double MOLD_RISK_HUMIDITY_THRESHOLD = 60.0;
    private static final double MOLD_RISK_HUMIDITY_CLEAR_THRESHOLD = 58.0;

    public void evaluate(CabinEvent event) {
        // This class's own WORKFLOW_ACTION output loops back through this
        // same consumer -- never re-match it (same guard shape as
        // AutomationRuleService's own default no-op case for AUTOMATION_ALERT).
        if (event.eventType().startsWith("WORKFLOW_")) return;

        for (String triggerId : resolveTriggerDefinitionIds(event)) {
            handleTriggerMatch(triggerId, event);
        }
        for (String clearedTriggerId : resolveClearedTriggerDefinitionIds(event)) {
            handleTriggerCleared(clearedTriggerId, event);
            String clearedVariantTriggerId = CLEARED_TRIGGER_IDS.get(clearedTriggerId);
            if (clearedVariantTriggerId != null) {
                handleTriggerMatch(clearedVariantTriggerId, event);
            }
        }
    }

    /**
     * Every trigger id this event's DETECTED side matches -- plural because
     * one TELEMETRY event can legitimately carry more than one already-true
     * condition at once (see class doc). Camera/Blink/freeze-risk aren't
     * simple field-equality matches so stay as explicit checks alongside
     * the FIELD_TRIGGERS table scan.
     */
    private List<String> resolveTriggerDefinitionIds(CabinEvent event) {
        List<String> ids = new ArrayList<>();
        for (FieldTrigger ft : FIELD_TRIGGERS) {
            if (ft.eventType().equals(event.eventType()) && Objects.equals(event.payload().get(ft.payloadField()), ft.detectedValue())) {
                ids.add(ft.detectedId());
            }
        }
        // DETECTION_NEW only, not UPDATE/END -- matches cabin_camera_event's
        // own documented shape (docs/ontology.yaml): NEW is Frigate first
        // recognizing an object, UPDATE/END are the same tracked object
        // continuing to be seen/leaving frame. A workflow should fire once
        // per new detection, not once per frame of an object already being
        // tracked -- findActive()'s edge-detection guard in handleTriggerMatch
        // would suppress repeats within one tracked object's lifetime anyway,
        // but matching only NEW keeps the intent explicit rather than relying
        // on that guard to paper over matching every UPDATE too.
        if ("DETECTION_NEW".equals(event.eventType())) {
            ids.add("trigger_camera_detection");
        }
        // E3 -- Blink's own MOTION_ON/MOTION_OFF are already distinct
        // CabinEvent.eventType() values (MqttBridgeService.handleCameraTopic()),
        // not a payload field -- a separate signal from Frigate's DETECTION_NEW.
        if ("MOTION_ON".equals(event.eventType())) {
            ids.add("trigger_blink_motion_detected");
        }
        // E2 -- see FREEZE_RISK_THRESHOLD_F's own comment for the hysteresis reasoning.
        if ("TELEMETRY".equals(event.eventType())
                && event.payload().get("temperature") instanceof Number n && n.doubleValue() < FREEZE_RISK_THRESHOLD_F) {
            ids.add("trigger_freeze_risk_detected");
        }
        // Mold risk -- see MOLD_RISK_HUMIDITY_THRESHOLD's own comment.
        if ("TELEMETRY".equals(event.eventType())
                && event.payload().get("humidity") instanceof Number h && h.doubleValue() >= MOLD_RISK_HUMIDITY_THRESHOLD) {
            ids.add("trigger_mold_risk_detected");
        }
        // E5 -- generic, deliberately unconditional on payload content
        // (any HA entity, any change) unlike every other trigger above.
        // Real device-scoping happens the same way trigger_camera_detection's
        // own doc already documents: ruleStore.findByTrigger() only returns
        // workflows whose triggerDeviceId is null (any) or matches this
        // exact sourceDeviceId -- a workflow meant for one specific HA
        // entity is scoped there, not here. "ha-" prefix matches
        // HomeAssistantDiscoveryService's own generatedId convention
        // ("ha-"+location+"-"+entityId), so this never matches a Zigbee
        // z2m- device's own telemetry even when unscoped. Can co-fire
        // alongside a more specific FIELD_TRIGGERS match on the same event
        // (e.g. an HA moisture sensor reporting water_leak:true matches
        // both trigger_water_leak_detected AND this) -- both are
        // independent, legitimate workflows if a person built both.
        if ("TELEMETRY".equals(event.eventType()) && event.sourceDeviceId() != null && event.sourceDeviceId().startsWith("ha-")) {
            ids.add("trigger_ha_entity_state_changed");
        }
        return ids;
    }

    /** The inverse of resolveTriggerDefinitionIds() -- an explicit cleared value, not merely absent, is what counts as "cleared." Returns the DETECTED id (matching the pre-2026-08-21 convention), not the cleared id -- see evaluate()'s own CLEARED_TRIGGER_IDS lookup for why. */
    private List<String> resolveClearedTriggerDefinitionIds(CabinEvent event) {
        List<String> ids = new ArrayList<>();
        for (FieldTrigger ft : FIELD_TRIGGERS) {
            if (ft.eventType().equals(event.eventType()) && Objects.equals(event.payload().get(ft.payloadField()), ft.clearedValue())) {
                ids.add(ft.detectedId());
            }
        }
        if ("MOTION_OFF".equals(event.eventType())) {
            ids.add("trigger_blink_motion_detected");
        }
        if ("TELEMETRY".equals(event.eventType())
                && event.payload().get("temperature") instanceof Number n && n.doubleValue() >= FREEZE_RISK_CLEAR_THRESHOLD_F) {
            ids.add("trigger_freeze_risk_detected");
        }
        if ("TELEMETRY".equals(event.eventType())
                && event.payload().get("humidity") instanceof Number h && h.doubleValue() < MOLD_RISK_HUMIDITY_CLEAR_THRESHOLD) {
            ids.add("trigger_mold_risk_detected");
        }
        return ids;
    }

    private void handleTriggerMatch(String triggerId, CabinEvent event) {
        List<WorkflowRule> matches = ruleStore.findByTrigger(triggerId, event.sourceDeviceId());
        for (WorkflowRule rule : matches) {
            if (executionStore.existsForTriggerEvent(rule.workflowId(), event.eventId())) {
                log.debug("Workflow '{}' already has an execution for event {}, skipping (idempotency)",
                    rule.workflowId(), event.eventId());
                continue;
            }
            if (executionStore.findActive(rule.workflowId()).isPresent()) {
                log.debug("Workflow '{}' already has an active execution, skipping re-fire (edge detection)",
                    rule.workflowId());
                continue;
            }
            fire(rule, event);
        }
    }

    /** Auto-clears any active execution for a matching AUTO_ON_CLEAR workflow -- a human can always also clear manually via POST .../clear regardless of resetMode. */
    private void handleTriggerCleared(String triggerId, CabinEvent event) {
        List<WorkflowRule> matches = ruleStore.findByTrigger(triggerId, event.sourceDeviceId());
        for (WorkflowRule rule : matches) {
            if (!"AUTO_ON_CLEAR".equals(rule.resetMode())) continue;
            executionStore.findActive(rule.workflowId()).ifPresent(exec -> {
                log.info("Workflow '{}' trigger condition cleared by event {}, auto-clearing execution {}",
                    rule.workflowId(), event.eventId(), exec.executionId());
                executionStore.save(new WorkflowExecution(
                    exec.executionId(), exec.workflowId(), exec.triggeredByEventId(), exec.firedAt(),
                    Instant.now(), "AUTO", exec.actionResults(), exec.lastViewedAt()));
            });
        }
    }

    /**
     * A DEVICE_EVENT trigger belongs in STATE_TRIGGERS_WITH_CLEAR_SIGNAL
     * (declared above, derived from FIELD_TRIGGERS) iff it represents an
     * ongoing, sampled STATE (water_leak, re-reported on every telemetry
     * tick) rather than a discrete EVENT (a camera detection, each
     * occurrence independent of the last). A state-trigger's execution must
     * stay "active" until that state actually clears (or a human clears it)
     * so the SAME ongoing condition doesn't re-notify on every sample --
     * findActive()'s edge-detection guard above is what that protects. A
     * trigger with no symmetric clear signal has no such repeat-sampling to
     * guard against, so fire() below self-clears it immediately: each
     * firing is already a distinct occurrence, and never auto-clearing
     * would mean exactly one notification, ever, per workflow (found
     * 2026-08-18 designing the camera-detection trigger against the
     * water-leak workflow's original, action-list-based first draft of
     * this rule, which wrongly self-cleared ANY workflow whose actions
     * were all notify_critical/log_event -- including the existing
     * MANUAL_ONLY notify-only leak workflow this class's own
     * manualOnlyResetModeDoesNotAutoClear test depends on staying active
     * until a human clears it. Scoping to the TRIGGER, not the action
     * list, is what keeps that test's invariant true while still giving
     * camera-detection workflows the "notify every time" behavior they
     * need).
     */
    private void fire(WorkflowRule rule, CabinEvent triggeringEvent) {
        log.info("Workflow '{}' ({}) firing on event {}", rule.name(), rule.workflowId(), triggeringEvent.eventId());
        String executionId = UUID.randomUUID().toString();
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkflowAction action : rule.actions()) {
            results.add(executeAction(executionId, action, rule, triggeringEvent));
        }
        boolean selfClears = !STATE_TRIGGERS_WITH_CLEAR_SIGNAL.contains(rule.triggerDefinitionId());
        Instant now = Instant.now();
        WorkflowExecution execution = new WorkflowExecution(
            executionId, rule.workflowId(), triggeringEvent.eventId(),
            now, selfClears ? now : null, selfClears ? "AUTO" : null, results, null);
        executionStore.save(execution);
    }

    /**
     * A human explicitly firing a MANUAL trigger_kind workflow -- e.g. the
     * "Reopen valve" workflow, the one legitimate way to reopen the main
     * water valve (validateReopenGuard() in RulesController keeps that
     * action out of every DEVICE_EVENT-triggered workflow, on purpose).
     * Reuses executeAction() exactly, but with no source CabinEvent to
     * point at -- triggeredByEventId is null, matching
     * JdbcWorkflowExecutionStore's own dedup-index comment ("MANUAL-trigger
     * executions legitimately have no source event and must not be limited
     * to firing once ever"): every tap creates a new execution, since a
     * human explicitly chose this one moment, not a repeat of the same
     * signal. Always self-clears immediately -- there's no ongoing sampled
     * condition for a manual fire to wait on.
     */
    public WorkflowExecution fireManual(WorkflowRule rule, String actorEmail) {
        log.info("Workflow '{}' ({}) manually fired by {}", rule.name(), rule.workflowId(), actorEmail);
        String executionId = UUID.randomUUID().toString();
        CabinEvent syntheticEvent = new CabinEvent(UUID.randomUUID().toString(), rule.location(),
            "WORKFLOW_MANUAL_FIRE", "INFO", Instant.now(), Map.of("firedBy", actorEmail));
        List<Map<String, Object>> results = new ArrayList<>();
        for (WorkflowAction action : rule.actions()) {
            results.add(executeAction(executionId, action, rule, syntheticEvent));
        }
        Instant now = Instant.now();
        WorkflowExecution execution = new WorkflowExecution(
            executionId, rule.workflowId(), null, now, now, "MANUAL:" + actorEmail, results, null);
        executionStore.save(execution);
        return execution;
    }

    private Map<String, Object> executeAction(String executionId, WorkflowAction action, WorkflowRule rule,
                                               CabinEvent triggeringEvent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionId", action.actionId());
        result.put("actionDefinitionId", action.actionDefinitionId());

        // Per-action cooldown (added 2026-08-21) -- independent of the
        // rule-level findActive() edge-detection guard above, which exists
        // for a different reason (don't re-fire while one ongoing sampled
        // state is unchanged). This is what lets e.g. a notify_critical
        // action (no cooldown set) re-fire on every occurrence while a
        // sibling actuator action on the same rule (a cooldown set) is
        // rate-limited or effectively one-shot.
        if (action.cooldownSeconds() != null) {
            Instant lastSuccess = mostRecentSuccessAt(rule.workflowId(), action.actionId());
            if (lastSuccess != null && lastSuccess.isAfter(Instant.now().minusSeconds(action.cooldownSeconds()))) {
                result.put("success", true);
                result.put("skipped", true);
                result.put("reason", "cooldown");
                return result;
            }
        }
        try {
            switch (action.actionDefinitionId()) {
                case "action_main_water_valve_off" -> executeValveCommand(executionId, action, "OFF", result);
                case "action_main_water_valve_open" -> executeValveCommand(executionId, action, "ON", result);
                case "log_event" ->
                    // Logging is automatic -- CabinEventService already persists
                    // every event this engine reacts to and every WORKFLOW_ACTION
                    // event it produces. This action kind exists purely so a
                    // human sees "log" as an explicit selected step.
                    result.put("success", true);
                case "notify_critical" -> {
                    publishNotification(rule, triggeringEvent);
                    result.put("success", true);
                }
                default -> {
                    result.put("success", false);
                    result.put("error", "Unknown actionDefinitionId: " + action.actionDefinitionId());
                }
            }
        } catch (Exception e) {
            log.warn("Workflow action {} failed: {}", action.actionId(), e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    /**
     * Most recent firedAt among this workflow's past executions where this
     * specific action recorded success:true -- the per-action cooldown
     * check above. Scans recent executions in Java rather than a
     * JSON-querying SQL clause, matching this store's existing "keep the
     * SQL layer simple" shape; cooldown windows are short and this list is
     * small. A skipped ("success":true,"skipped":true) result does NOT
     * count as a success here -- it would otherwise indefinitely extend its
     * own cooldown from a run that never actually did anything.
     */
    private Instant mostRecentSuccessAt(String workflowId, String actionId) {
        for (WorkflowExecution exec : executionStore.recentFor(workflowId, 20)) {
            for (Map<String, Object> r : exec.actionResults()) {
                if (actionId.equals(r.get("actionId")) && Boolean.TRUE.equals(r.get("success"))
                        && !Boolean.TRUE.equals(r.get("skipped"))) {
                    return exec.firedAt();
                }
            }
        }
        return null;
    }

    /**
     * Issues the command and marks it ACCEPTED (the MQTT publish succeeded)
     * -- NOT proof the device actually moved. Schedules an async re-check
     * that upgrades this same action's result to CONFIRMED/UNCONFIRMED once
     * the device's own reported state either matches or the timeout elapses.
     */
    private void executeValveCommand(String executionId, WorkflowAction action, String state, Map<String, Object> result) {
        String deviceId = action.targetDeviceId();
        if (!commandCatalog.isCommandAllowed(deviceId)) {
            result.put("success", false);
            result.put("commandStatus", "REJECTED");
            result.put("error", "Device " + deviceId + " is not command-capable or not found");
            return;
        }
        boolean published = deviceRegistry.sendCommand(deviceId, "state.set", Map.of("state", state));
        result.put("success", published);
        result.put("deviceId", deviceId);
        result.put("commandedState", state);
        result.put("commandStatus", published ? "ACCEPTED" : "PUBLISH_FAILED");
        if (published) {
            scheduleConfirmation(executionId, action.actionId(), deviceId, state, false);
        }
    }

    private void scheduleConfirmation(String executionId, String actionId, String deviceId, String expectedState,
                                       boolean isFinalAttempt) {
        long delay = isFinalAttempt
            ? Math.max(0, confirmationFinalDelayMillis - confirmationInitialDelayMillis)
            : confirmationInitialDelayMillis;
        confirmationScheduler.schedule(
            () -> checkConfirmation(executionId, actionId, deviceId, expectedState, isFinalAttempt),
            delay, TimeUnit.MILLISECONDS);
    }

    /** Package-private so tests can invoke this synchronously instead of waiting on the real scheduler. */
    void checkConfirmation(String executionId, String actionId, String deviceId, String expectedState,
                            boolean isFinalAttempt) {
        DeviceStatus current = deviceRegistry.get(deviceId);
        Object currentState = current == null ? null : current.attributes().get("state");
        boolean confirmed = expectedState.equalsIgnoreCase(String.valueOf(currentState));

        if (!confirmed && !isFinalAttempt) {
            scheduleConfirmation(executionId, actionId, deviceId, expectedState, true);
            return;
        }

        Optional<WorkflowExecution> existing = executionStore.findById(executionId);
        if (existing.isEmpty()) return; // execution was never saved or was somehow removed -- nothing to update
        WorkflowExecution exec = existing.get();
        List<Map<String, Object>> updatedResults = new ArrayList<>();
        for (Map<String, Object> r : exec.actionResults()) {
            if (actionId.equals(r.get("actionId"))) {
                Map<String, Object> updated = new LinkedHashMap<>(r);
                updated.put("commandStatus", confirmed ? "CONFIRMED" : "UNCONFIRMED");
                updatedResults.add(updated);
            } else {
                updatedResults.add(r);
            }
        }
        executionStore.save(new WorkflowExecution(
            exec.executionId(), exec.workflowId(), exec.triggeredByEventId(), exec.firedAt(),
            exec.clearedAt(), exec.clearedBy(), updatedResults, exec.lastViewedAt()));

        if (!confirmed) {
            log.warn("Command on {} (execution {}) UNCONFIRMED after {}ms -- device never reported the expected state",
                deviceId, executionId, confirmationFinalDelayMillis);
            publishUnconfirmedAlert(deviceId, expectedState, executionId);
        } else {
            log.info("Command on {} (execution {}) CONFIRMED", deviceId, executionId);
        }
    }

    private void publishUnconfirmedAlert(String deviceId, String expectedState, String executionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ruleId", "WORKFLOW_UNCONFIRMED_" + executionId);
        payload.put("see", "Commanded device did not confirm the expected state");
        payload.put("think", "Sent " + expectedState + " to " + deviceId + " but its reported state never matched within "
            + confirmationFinalDelayMillis + "ms");
        payload.put("act", "Check the device physically -- this command may not have taken effect");
        payload.put("tags", List.of("WORKFLOW", "UNCONFIRMED"));
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), deviceId, "WORKFLOW_UNCONFIRMED", "CRITICAL", Instant.now(), payload));
    }

    /**
     * Reuses AutomationRuleService's exact see/think/act/tags payload shape
     * so NtfyAlertPublisher/AutomationAlertCard need zero changes.
     *
     * "see"/"act" were hardcoded to the water-leak flagship scenario's own
     * text ("Water leak detected" / "Notify + shut off main water valve")
     * until 2026-08-18 -- harmless while notify_critical only ever fired
     * from that one trigger, but a real, silent-until-triggered bug: any
     * other trigger type using this same action (e.g. a camera detection)
     * would have sent a notification falsely describing a water leak. No
     * test pinned the literal strings (checked before changing this), so
     * nothing to update alongside this fix.
     */
    private void publishNotification(WorkflowRule rule, CabinEvent triggeringEvent) {
        Map<String, Object> payload = new LinkedHashMap<>(triggeringEvent.payload());
        payload.put("ruleId", "WORKFLOW_" + rule.workflowId());
        payload.put("see", describeTriggeringEvent(triggeringEvent));
        payload.put("think", "Human-configured workflow '" + rule.name() + "' matched this event");
        payload.put("act", describeActions(rule));
        payload.put("tags", List.of("WORKFLOW"));
        eventPublisher.publish(new CabinEvent(
            UUID.randomUUID().toString(), triggeringEvent.sourceDeviceId(), "WORKFLOW_ACTION", "CRITICAL",
            Instant.now(), payload));
    }

    /**
     * Human-readable "what happened" for the event that fired this
     * workflow -- one case per resolveTriggerDefinitionIds()/
     * resolveClearedTriggerDefinitionIds() branch. Checks FIELD_TRIGGERS
     * first (covers both the detected and cleared side of every table
     * row, e.g. this now correctly says "Water leak cleared" for a
     * cleared-side notification instead of falling through to the
     * generic deviceId:eventType text below, a real improvement found
     * while generalizing this method, not just parity).
     */
    private String describeTriggeringEvent(CabinEvent event) {
        for (FieldTrigger ft : FIELD_TRIGGERS) {
            if (!ft.eventType().equals(event.eventType())) continue;
            Object value = event.payload().get(ft.payloadField());
            if (Objects.equals(value, ft.detectedValue()) && ft.detectedDescription() != null) {
                return ft.detectedDescription();
            }
            if (Objects.equals(value, ft.clearedValue()) && ft.clearedDescription() != null) {
                return ft.clearedDescription();
            }
        }
        if (event.eventType() != null && event.eventType().startsWith("DETECTION_")) {
            Object label = event.payload().get("label");
            Object score = event.payload().get("score");
            String pct = score instanceof Number n ? " (" + Math.round(n.doubleValue() * 100) + "%)" : "";
            return event.sourceDeviceId() + " detected " + (label != null ? label : "activity") + pct;
        }
        if ("MOTION_ON".equals(event.eventType())) {
            return event.sourceDeviceId() + " camera motion detected";
        }
        if ("MOTION_OFF".equals(event.eventType())) {
            return event.sourceDeviceId() + " camera motion cleared";
        }
        if ("TELEMETRY".equals(event.eventType()) && event.payload().get("temperature") instanceof Number n) {
            if (n.doubleValue() < FREEZE_RISK_THRESHOLD_F) return "Freeze risk: temperature dropped to " + n + "°F";
            if (n.doubleValue() >= FREEZE_RISK_CLEAR_THRESHOLD_F) return "Freeze risk cleared: temperature back up to " + n + "°F";
        }
        if ("TELEMETRY".equals(event.eventType()) && event.payload().get("humidity") instanceof Number h) {
            if (h.doubleValue() >= MOLD_RISK_HUMIDITY_THRESHOLD) return "Mold risk: humidity at " + h + "%";
            if (h.doubleValue() < MOLD_RISK_HUMIDITY_CLEAR_THRESHOLD) return "Mold risk cleared: humidity back down to " + h + "%";
        }
        // PRESENCE_CHANGED's description is dynamic (per-person), not a
        // static FIELD_TRIGGERS string -- see that row's own comment.
        if ("PRESENCE_CHANGED".equals(event.eventType())) {
            Object personId = event.payload().get("personId");
            boolean present = Boolean.TRUE.equals(event.payload().get("present"));
            return (personId != null ? personId : "Someone") + (present ? " arrived" : " departed");
        }
        if ("WORKFLOW_MANUAL_FIRE".equals(event.eventType())) {
            return "Manually fired by " + event.payload().get("firedBy");
        }
        // E5's generic HA-entity trigger -- haState is what
        // HomeAssistantDiscoveryService.publishIfChanged() adds alongside
        // the raw attrs, see that method's own doc.
        if ("TELEMETRY".equals(event.eventType()) && event.payload().get("haState") != null) {
            return event.sourceDeviceId() + " changed to " + event.payload().get("haState");
        }
        return event.sourceDeviceId() + ": " + event.eventType();
    }

    /** Human-readable "what this workflow does" -- one case per executeAction()'s own switch, kept in sync with it deliberately (not derived reflectively) so an unmapped id fails loud (falls through to its own raw id) rather than silently miscasting a real action as something it isn't. */
    private String describeActions(WorkflowRule rule) {
        return rule.actions().stream()
            .map(a -> switch (a.actionDefinitionId()) {
                case "action_main_water_valve_off" -> "Shut off main water valve";
                case "action_main_water_valve_open" -> "Reopen main water valve";
                case "notify_critical" -> "Notify";
                case "log_event" -> "Log";
                default -> a.actionDefinitionId();
            })
            .distinct()
            .reduce((a, b) -> a + " + " + b)
            .orElse("no actions");
    }

    @PreDestroy
    public void shutdown() {
        confirmationScheduler.shutdownNow();
    }
}
