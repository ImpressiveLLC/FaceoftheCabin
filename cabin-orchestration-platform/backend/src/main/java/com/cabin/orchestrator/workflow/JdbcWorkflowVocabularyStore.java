package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.workflow.model.ActionVocabularyEntry;
import com.cabin.orchestrator.workflow.model.TriggerVocabularyEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * The real, DB-backed answer to "what can a workflow actually be built
 * against on this instance" -- added 2026-08-21 after the user reported
 * WorkflowCreateForm's dropdowns were hardcoded JS with no traceability to
 * the ontology, and asked for exactly this: "a DB table owned by the
 * system... on the M920q (or agnostic future device, in the case of
 * cloned versions of the system for other users)."
 *
 * Same idempotent CREATE TABLE IF NOT EXISTS pattern as
 * JdbcWorkflowRuleStore -- every cabin-backend/home-backend deployment
 * (and any future docs/REPLICATION.md clone) gets its own copy of this
 * table automatically on first boot, nothing to hand-configure.
 *
 * Deliberately has NO write endpoint anywhere in this codebase. Truth
 * lives in code -- this table is reseeded (idempotent upsert) from the
 * SUPPORTED_TRIGGERS/SUPPORTED_ACTIONS lists below on every startup,
 * which must be kept in hand-sync with WorkflowRuleService's own
 * resolveTriggerDefinitionId()/executeAction() switch statements (same
 * discipline this project already applies to keeping docs/ontology.yaml
 * and the Java engine in sync elsewhere -- see notify_critical/log_event's
 * own ontology notes about a prior drift). This exists so the *frontend*
 * has a real thing to query, not so an admin can redefine what the engine
 * does -- see ActionVocabularyEntry's own doc for why the privileged flag
 * specifically must never become editable.
 */
@Repository
public class JdbcWorkflowVocabularyStore {

    // 2026-08-21 (Part E) -- expanded from 3 to 19 after the user reported
    // only water-leak/camera existed as real trigger options despite far
    // more already flowing. Every label here matches
    // WorkflowRuleService.FIELD_TRIGGERS'/describeTriggeringEvent()'s own
    // text exactly (kept in sync by hand, same discipline as everything
    // else in this class) so the dropdown label and the eventual
    // notification's "see" text never disagree.
    private static final List<TriggerVocabularyEntry> SUPPORTED_TRIGGERS = List.of(
        new TriggerVocabularyEntry("trigger_water_leak_detected", "Water leak detected", "WATER_LEAK_SENSOR", "ALARM", true),
        new TriggerVocabularyEntry("trigger_water_leak_cleared", "Water leak cleared", "WATER_LEAK_SENSOR", "ALARM", true),
        new TriggerVocabularyEntry("trigger_camera_detection", "Camera detects motion", "CAMERA", "STREAM", true),
        new TriggerVocabularyEntry("trigger_door_contact_opened", "Door contact opened", "CONTACT_SENSOR", "ACCESS_CONTROL", true),
        new TriggerVocabularyEntry("trigger_door_contact_closed", "Door contact closed", "CONTACT_SENSOR", "ACCESS_CONTROL", true),
        new TriggerVocabularyEntry("trigger_motion_detected", "Motion detected", "MOTION_SENSOR", "PRESENCE", true),
        new TriggerVocabularyEntry("trigger_motion_cleared", "Motion cleared", "MOTION_SENSOR", "PRESENCE", true),
        // Tamper/battery-low apply to any device reporting that field, not
        // one device type -- appliesToDeviceType stays null deliberately
        // (the creation form falls back to showing every visible device
        // when it's null, same as before device-type filtering existed).
        new TriggerVocabularyEntry("trigger_tamper_detected", "Tamper detected", null, "ALARM", true),
        new TriggerVocabularyEntry("trigger_tamper_cleared", "Tamper cleared", null, "ALARM", true),
        new TriggerVocabularyEntry("trigger_battery_low_detected", "Battery low", null, null, true),
        new TriggerVocabularyEntry("trigger_battery_low_cleared", "Battery no longer low", null, null, true),
        new TriggerVocabularyEntry("trigger_plug_turned_on", "Device turned on", "POWER_METER", "COMMAND", true),
        new TriggerVocabularyEntry("trigger_plug_turned_off", "Device turned off", "POWER_METER", "COMMAND", true),
        new TriggerVocabularyEntry("trigger_freeze_risk_detected", "Freeze risk (below 32°F)", "TEMPERATURE_SENSOR", "TELEMETRY", true),
        new TriggerVocabularyEntry("trigger_freeze_risk_cleared", "Freeze risk cleared (36°F or above)", "TEMPERATURE_SENSOR", "TELEMETRY", true),
        // appliesToDeviceType is deliberately null (any), unlike freeze-risk's
        // single TEMPERATURE_SENSOR -- humidity is reported by BOTH
        // TEMPERATURE_SENSOR (Zigbee combo sensors) and HUMIDITY_SENSOR
        // (Kidde's dedicated entity), and DmSeeView/the create-workflow
        // form's triggerScopedDevices only ever matches one exact type
        // string -- null keeps every humidity-reporting device selectable
        // for scoping, regardless of which of the two types it is.
        new TriggerVocabularyEntry("trigger_mold_risk_detected", "Mold risk (60% humidity or above)", null, "TELEMETRY", true),
        new TriggerVocabularyEntry("trigger_mold_risk_cleared", "Mold risk cleared (below 58% humidity)", null, "TELEMETRY", true),
        new TriggerVocabularyEntry("trigger_blink_motion_detected", "Camera motion detected (Blink)", "CAMERA", "STREAM", true),
        new TriggerVocabularyEntry("trigger_blink_motion_cleared", "Camera motion cleared (Blink)", "CAMERA", "STREAM", true),
        // Armed/presence are location- and person-scoped, not device-scoped
        // -- appliesToDeviceType stays null, same reasoning as tamper/battery.
        new TriggerVocabularyEntry("trigger_security_armed", "Armed away", null, null, true),
        new TriggerVocabularyEntry("trigger_security_disarmed", "Disarmed", null, null, true),
        new TriggerVocabularyEntry("trigger_presence_arrived", "Someone arrived", null, null, true),
        new TriggerVocabularyEntry("trigger_presence_departed", "Someone departed", null, null, true),
        // E5's generic HA-entity trigger -- no clear-signal pair (discrete,
        // any change), always meant to be scoped via triggerDeviceId (see
        // its own docs/ontology.yaml entry).
        new TriggerVocabularyEntry("trigger_ha_entity_state_changed", "Home Assistant entity changed", null, null, true),
        // Added 2026-08-21 -- real push bridge deployed (new HA automation
        // + MqttBridgeService.handleKiddeCoAlarmTopic()), was
        // docs/ontology.yaml's trigger_kidde_co_alarm candidate until now.
        new TriggerVocabularyEntry("trigger_kidde_co_alarm", "Kidde CO alarm active", "CO_ALARM", "ALARM", true),
        new TriggerVocabularyEntry("trigger_kidde_co_alarm_cleared", "Kidde CO alarm cleared", "CO_ALARM", "ALARM", true)
    );

    private static final List<ActionVocabularyEntry> SUPPORTED_ACTIONS = List.of(
        new ActionVocabularyEntry("action_main_water_valve_off", "Shut off the main water valve",
            "command", "COMMAND", true, "z2m-main_water_valve", false, true),
        new ActionVocabularyEntry("action_main_water_valve_open", "Open the main water valve",
            "command", "COMMAND", true, "z2m-main_water_valve", true, true),
        new ActionVocabularyEntry("notify_critical", "Send a critical notification",
            "notify", null, false, null, false, true),
        new ActionVocabularyEntry("log_event", "Log this event only",
            "log", null, false, null, false, true)
    );

    private final JdbcTemplate jdbc;

    public JdbcWorkflowVocabularyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS trigger_vocabulary (
              id                      TEXT PRIMARY KEY,
              label                   TEXT NOT NULL,
              applies_to_device_type  TEXT,
              applies_to_capability   TEXT,
              supported               BOOLEAN NOT NULL DEFAULT true
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS action_vocabulary (
              id                    TEXT PRIMARY KEY,
              label                 TEXT NOT NULL,
              action_kind           TEXT NOT NULL,
              requires_capability   TEXT,
              needs_target          BOOLEAN NOT NULL DEFAULT false,
              target_device_id      TEXT,
              privileged            BOOLEAN NOT NULL DEFAULT false,
              supported             BOOLEAN NOT NULL DEFAULT true
            )""");
        reseed();
    }

    private void reseed() {
        for (TriggerVocabularyEntry t : SUPPORTED_TRIGGERS) {
            jdbc.update("""
                INSERT INTO trigger_vocabulary (id, label, applies_to_device_type, applies_to_capability, supported)
                VALUES (?, ?, ?, ?, true)
                ON CONFLICT (id) DO UPDATE SET
                  label = EXCLUDED.label, applies_to_device_type = EXCLUDED.applies_to_device_type,
                  applies_to_capability = EXCLUDED.applies_to_capability, supported = true
                """, t.id(), t.label(), t.appliesToDeviceType(), t.appliesToCapability());
        }
        for (ActionVocabularyEntry a : SUPPORTED_ACTIONS) {
            jdbc.update("""
                INSERT INTO action_vocabulary (id, label, action_kind, requires_capability, needs_target, target_device_id, privileged, supported)
                VALUES (?, ?, ?, ?, ?, ?, ?, true)
                ON CONFLICT (id) DO UPDATE SET
                  label = EXCLUDED.label, action_kind = EXCLUDED.action_kind, requires_capability = EXCLUDED.requires_capability,
                  needs_target = EXCLUDED.needs_target, target_device_id = EXCLUDED.target_device_id,
                  privileged = EXCLUDED.privileged, supported = true
                """, a.id(), a.label(), a.actionKind(), a.requiresCapability(), a.needsTarget(), a.targetDeviceId(), a.privileged());
        }
    }

    public List<TriggerVocabularyEntry> loadSupportedTriggers() {
        return jdbc.query("SELECT id, label, applies_to_device_type, applies_to_capability, supported FROM trigger_vocabulary WHERE supported ORDER BY id",
            (rs, i) -> new TriggerVocabularyEntry(
                rs.getString("id"), rs.getString("label"),
                rs.getString("applies_to_device_type"), rs.getString("applies_to_capability"), true));
    }

    public List<ActionVocabularyEntry> loadSupportedActions() {
        return jdbc.query("SELECT id, label, action_kind, requires_capability, needs_target, target_device_id, privileged, supported FROM action_vocabulary WHERE supported ORDER BY id",
            (rs, i) -> new ActionVocabularyEntry(
                rs.getString("id"), rs.getString("label"), rs.getString("action_kind"), rs.getString("requires_capability"),
                rs.getBoolean("needs_target"), rs.getString("target_device_id"), rs.getBoolean("privileged"), true));
    }
}
