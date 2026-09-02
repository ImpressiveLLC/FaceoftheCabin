package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceLifecycleAction;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.LifecycleActionVocabularyEntry;
import com.cabin.orchestrator.devices.model.LifecycleStateVocabularyEntry;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real point of this class: the seeded boolean/target-state columns
 * must always match DeviceLifecycleState/DeviceLifecycleAction's own
 * predicate methods, not a hand-duplicated copy -- see the store's own
 * doc for why that anti-drift guarantee is the whole reason this exists.
 */
@Testcontainers
class JdbcDeviceLifecycleVocabularyStoreTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private JdbcDeviceLifecycleVocabularyStore newStore() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        return new JdbcDeviceLifecycleVocabularyStore(jdbc);
    }

    @Test
    void seedsOneRowPerLifecycleStateMatchingItsOwnRealPredicates() {
        List<LifecycleStateVocabularyEntry> states = newStore().loadStates();

        assertEquals(DeviceLifecycleState.values().length, states.size());
        Map<String, LifecycleStateVocabularyEntry> byId = states.stream()
            .collect(java.util.stream.Collectors.toMap(LifecycleStateVocabularyEntry::id, e -> e));

        for (DeviceLifecycleState state : DeviceLifecycleState.values()) {
            LifecycleStateVocabularyEntry entry = byId.get(state.name());
            assertNotNull(entry, "missing vocabulary row for " + state);
            assertEquals(state.isInScope(), entry.inScope(), state + ".inScope mismatch");
            assertEquals(state.isPreviouslyExposed(), entry.previouslyExposed(), state + ".previouslyExposed mismatch");
            assertEquals(state.allowsActiveUse(), entry.allowsActiveUse(), state + ".allowsActiveUse mismatch");
            assertFalse(entry.label().isBlank(), state + " must have a human-readable label");
        }
    }

    @Test
    void seedsOneRowPerLifecycleActionMatchingItsOwnRealTargetState() {
        List<LifecycleActionVocabularyEntry> actions = newStore().loadActions();

        assertEquals(DeviceLifecycleAction.values().length, actions.size());
        Map<String, LifecycleActionVocabularyEntry> byId = actions.stream()
            .collect(java.util.stream.Collectors.toMap(LifecycleActionVocabularyEntry::id, e -> e));

        for (DeviceLifecycleAction action : DeviceLifecycleAction.values()) {
            LifecycleActionVocabularyEntry entry = byId.get(action.name());
            assertNotNull(entry, "missing vocabulary row for " + action);
            assertEquals(action.targetState().name(), entry.targetState(), action + ".targetState mismatch");
            assertFalse(entry.label().isBlank(), action + " must have a human-readable label");
        }
    }

    @Test
    void reseedingOnANewInstanceDoesNotDuplicateRows() {
        newStore();
        JdbcDeviceLifecycleVocabularyStore restarted = newStore();

        assertEquals(DeviceLifecycleState.values().length, restarted.loadStates().size());
        assertEquals(DeviceLifecycleAction.values().length, restarted.loadActions().size());
    }
}
