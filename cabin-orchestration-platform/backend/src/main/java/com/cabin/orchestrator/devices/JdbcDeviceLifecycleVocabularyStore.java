package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.LifecycleActionVocabularyEntry;
import com.cabin.orchestrator.devices.model.LifecycleStateVocabularyEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * The real, DB-backed answer to "what device lifecycle states/actions exist
 * on this instance" -- the one-stop source of truth the main_water_valve
 * investigation found missing (Claude had to grep DeviceLifecycleState.java
 * and DeviceLifecycleAction.java directly to answer basic questions about
 * valid states). Mirrors JdbcWorkflowVocabularyStore's established pattern:
 * idempotent CREATE TABLE IF NOT EXISTS, reseeded from code on every boot,
 * ON CONFLICT DO UPDATE, never deletes, no write endpoint.
 *
 * Unlike JdbcWorkflowVocabularyStore, the boolean/target-state columns are
 * NOT hand-duplicated in a second Java list -- they're read directly from
 * DeviceLifecycleState/DeviceLifecycleAction's own predicate methods
 * (isInScope(), isPreviouslyExposed(), allowsActiveUse(), targetState()),
 * so this table can never drift from what DeviceRegistry actually enforces.
 * Only the human-readable label needs hand-authoring.
 */
@Repository
public class JdbcDeviceLifecycleVocabularyStore {

    // Kept in sync by hand with App.jsx's existing LIFECYCLE_LABELS / device-
    // action button text -- this table replaces those hardcoded copies, not
    // the other way around, so the wording matches what's already shipped.
    private static final Map<DeviceLifecycleState, String> STATE_LABELS = Map.of(
        DeviceLifecycleState.CANDIDATE, "Candidates",
        DeviceLifecycleState.AVAILABLE, "Available",
        DeviceLifecycleState.ASSIGNED, "Assigned",
        DeviceLifecycleState.DEFERRED, "Saved for later",
        DeviceLifecycleState.IGNORED, "Ignored"
    );

    private static final Map<DeviceLifecycleAction, String> ACTION_LABELS = Map.of(
        DeviceLifecycleAction.ACCEPT, "Use this device",
        DeviceLifecycleAction.DEFER, "Not now",
        DeviceLifecycleAction.IGNORE, "Ignore",
        DeviceLifecycleAction.REVIEW, "Return to candidates"
    );

    private final JdbcTemplate jdbc;

    public JdbcDeviceLifecycleVocabularyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lifecycle_state_vocabulary (
              id                    TEXT PRIMARY KEY,
              label                 TEXT NOT NULL,
              in_scope              BOOLEAN NOT NULL,
              previously_exposed    BOOLEAN NOT NULL,
              allows_active_use     BOOLEAN NOT NULL
            )""");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS lifecycle_action_vocabulary (
              id            TEXT PRIMARY KEY,
              label         TEXT NOT NULL,
              target_state  TEXT NOT NULL
            )""");
        reseed();
    }

    private void reseed() {
        for (DeviceLifecycleState state : DeviceLifecycleState.values()) {
            jdbc.update("""
                INSERT INTO lifecycle_state_vocabulary (id, label, in_scope, previously_exposed, allows_active_use)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  label = EXCLUDED.label, in_scope = EXCLUDED.in_scope,
                  previously_exposed = EXCLUDED.previously_exposed, allows_active_use = EXCLUDED.allows_active_use
                """, state.name(), STATE_LABELS.get(state), state.isInScope(), state.isPreviouslyExposed(), state.allowsActiveUse());
        }
        for (DeviceLifecycleAction action : DeviceLifecycleAction.values()) {
            jdbc.update("""
                INSERT INTO lifecycle_action_vocabulary (id, label, target_state)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET
                  label = EXCLUDED.label, target_state = EXCLUDED.target_state
                """, action.name(), ACTION_LABELS.get(action), action.targetState().name());
        }
    }

    public List<LifecycleStateVocabularyEntry> loadStates() {
        return jdbc.query("SELECT id, label, in_scope, previously_exposed, allows_active_use FROM lifecycle_state_vocabulary ORDER BY id",
            (rs, i) -> new LifecycleStateVocabularyEntry(
                rs.getString("id"), rs.getString("label"),
                rs.getBoolean("in_scope"), rs.getBoolean("previously_exposed"), rs.getBoolean("allows_active_use")));
    }

    public List<LifecycleActionVocabularyEntry> loadActions() {
        return jdbc.query("SELECT id, label, target_state FROM lifecycle_action_vocabulary ORDER BY id",
            (rs, i) -> new LifecycleActionVocabularyEntry(
                rs.getString("id"), rs.getString("label"), rs.getString("target_state")));
    }
}
