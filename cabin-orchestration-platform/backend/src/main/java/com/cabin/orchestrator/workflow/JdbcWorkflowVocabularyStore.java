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

    private static final List<TriggerVocabularyEntry> SUPPORTED_TRIGGERS = List.of(
        new TriggerVocabularyEntry("trigger_water_leak_detected", "Water leak detected", "WATER_LEAK_SENSOR", "ALARM", true),
        new TriggerVocabularyEntry("trigger_water_leak_cleared", "Water leak cleared", "WATER_LEAK_SENSOR", "ALARM", true),
        new TriggerVocabularyEntry("trigger_camera_detection", "Camera detects motion", "CAMERA", "STREAM", true)
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
