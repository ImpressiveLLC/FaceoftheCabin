package com.cabin.orchestrator.presence;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.kafka.EventPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** W-38 slice 1 / D24: presence counts primary-role persons only; a maintenance visit is recorded, not counted. */
class LocationRolePresenceTest {

    /** In-memory store, so these run without Docker. */
    static class MemoryStore implements TrackedPersonStore {
        final Map<String, Set<LocationRole>> rows = new HashMap<>();
        boolean failing;

        public List<Assignment> findAll() {
            List<Assignment> out = new ArrayList<>();
            rows.forEach((k, v) -> {
                String[] p = k.split("@");
                out.add(new Assignment(p[0], p[1], v, null));
            });
            return out;
        }

        public Set<LocationRole> rolesOf(String personId, String location) {
            if (failing) throw new IllegalStateException("db down");
            return rows.getOrDefault(personId + "@" + location, Set.of());
        }

        public void setRoles(String personId, String location, Set<LocationRole> roles) {
            if (roles.isEmpty()) rows.remove(personId + "@" + location);
            else rows.put(personId + "@" + location, EnumSet.copyOf(roles));
        }
    }

    private final MemoryStore store = new MemoryStore();
    private final EventPublisher events = mock(EventPublisher.class);
    private final PresenceSignalRegistry registry = new PresenceSignalRegistry(new LocationRoleService(store), events);

    @Test
    void aMaintenanceOnlySignalIsNotCountedAsPresence() {
        store.setRoles("contractor", "cabin", EnumSet.of(LocationRole.MAINTENANCE));
        registry.record("cabin", "contractor", true);

        assertThat(registry.anyPresentAt("cabin")).isFalse();
        assertThat(registry.all()).isEmpty();
        assertThat(registry.hasAnySignal()).isFalse();
    }

    @Test
    void aMaintenanceArrivalIsRecordedOnceAsAnInfoAuditEventWithWhoAndWhereOnly() {
        store.setRoles("contractor", "cabin", EnumSet.of(LocationRole.MAINTENANCE));
        registry.record("cabin", "contractor", true);
        registry.record("cabin", "contractor", true); // the next minute's heartbeat: still the same visit

        ArgumentCaptor<CabinEvent> event = ArgumentCaptor.forClass(CabinEvent.class);
        verify(events, times(1)).publish(event.capture());
        assertThat(event.getValue().eventType()).isEqualTo("MAINTENANCE_VISIT");
        assertThat(event.getValue().severity()).isEqualTo("INFO");
        assertThat(event.getValue().payload()).containsOnlyKeys("personId", "location");

        registry.record("cabin", "contractor", false);
        registry.record("cabin", "contractor", true); // a second arrival is a second visit
        verify(events, times(2)).publish(any());
    }

    @Test
    void aPrimaryPersonCountsAndRaisesNoFlag() {
        store.setRoles("nate", "cabin", EnumSet.of(LocationRole.PRIMARY));
        registry.record("cabin", "nate", true);

        assertThat(registry.anyPresentAt("cabin")).isTrue();
        assertThat(registry.unregisteredCounted()).isEmpty();
        verify(events, never()).publish(any());
    }

    @Test
    void holdingBothRolesCountsBecausePrimaryWins() {
        store.setRoles("nate", "cabin", EnumSet.of(LocationRole.PRIMARY, LocationRole.MAINTENANCE));
        registry.record("cabin", "nate", true);

        assertThat(registry.anyPresentAt("cabin")).isTrue();
        verify(events, never()).publish(any());
    }

    @Test
    void anUnregisteredPersonIsStillCountedButFlagged() {
        registry.record("cabin", "stranger-phone", true);

        assertThat(registry.anyPresentAt("cabin")).isTrue();
        assertThat(registry.unregisteredCounted()).extracting(PresenceSignalRegistry.Signal::personId).containsExactly("stranger-phone");

        store.setRoles("stranger-phone", "cabin", EnumSet.of(LocationRole.PRIMARY));
        registry.record("cabin", "stranger-phone", true);
        assertThat(registry.unregisteredCounted()).isEmpty();
    }

    @Test
    void rolesAreTiedToOneLocation() {
        store.setRoles("nate", "cabin", EnumSet.of(LocationRole.PRIMARY));
        store.setRoles("nate", "home", EnumSet.of(LocationRole.MAINTENANCE));
        registry.record("cabin", "nate", true);
        registry.record("home", "nate", true);

        assertThat(registry.anyPresentAt("cabin")).isTrue();
        assertThat(registry.anyPresentAt("home")).isFalse();
    }

    @Test
    void whenAPersonBecomesMaintenanceOnlyTheirStoredSignalStopsCounting() {
        registry.record("cabin", "nate", true);
        assertThat(registry.anyPresentAt("cabin")).isTrue();

        store.setRoles("nate", "cabin", EnumSet.of(LocationRole.MAINTENANCE));
        registry.record("cabin", "nate", true);
        assertThat(registry.anyPresentAt("cabin")).isFalse();
    }

    @Test
    void anUnreadableRegistryNeverStopsAPersonFromCounting() {
        store.failing = true;
        registry.record("cabin", "nate", true);

        assertThat(registry.anyPresentAt("cabin")).isTrue();
    }

    @Test
    void withoutARoleServiceEverySignalIsCountedAsBefore() {
        PresenceSignalRegistry legacy = new PresenceSignalRegistry();
        legacy.record("cabin", "anyone", true);

        assertThat(legacy.anyPresentAt("cabin")).isTrue();
        assertThat(legacy.unregisteredCounted()).isEmpty();
    }
}
