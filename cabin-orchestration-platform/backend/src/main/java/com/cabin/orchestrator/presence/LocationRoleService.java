package com.cabin.orchestrator.presence;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

/**
 * D24 R-LR-2: presence counts primary-role persons only. This answers, for one signal, how the hub should treat it.
 * Unregistered persons are COUNTED but flagged (Cowork ruling 2026-10-30 on W-38): the siren gate fails safe and nothing
 * silently stops being recognized at deploy. Exit rule: once the registry is seeded, an unregistered signal is still
 * counted, but it raises the flag so the person can be assigned a role.
 */
@Service
public class LocationRoleService {

    public enum Treatment {
        /** Registered with the primary role: counts as presence. */
        COUNTED,
        /** Not in the registry: counts as presence, and is reported as unregistered. */
        UNREGISTERED_COUNTED,
        /** Registered with the maintenance role only: a guest for presence; an arrival is recorded as a visit. */
        MAINTENANCE_ONLY
    }

    private final TrackedPersonStore store;

    public LocationRoleService(TrackedPersonStore store) {
        this.store = store;
    }

    public Treatment treatmentOf(String personId, String location) {
        Set<LocationRole> roles = store.rolesOf(personId, location);
        if (roles.isEmpty()) return Treatment.UNREGISTERED_COUNTED;
        return roles.contains(LocationRole.PRIMARY) ? Treatment.COUNTED : Treatment.MAINTENANCE_ONLY;
    }

    public List<TrackedPersonStore.Assignment> assignments() {
        return store.findAll();
    }

    public void assign(String personId, String location, Set<LocationRole> roles) {
        if (personId == null || personId.isBlank()) throw new IllegalArgumentException("personId is required");
        if (location == null || location.isBlank()) throw new IllegalArgumentException("location is required");
        store.setRoles(personId.trim(), location.trim(), roles == null ? Set.of() : roles);
    }
}
