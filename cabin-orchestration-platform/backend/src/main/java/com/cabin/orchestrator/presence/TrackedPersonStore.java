package com.cabin.orchestrator.presence;

import java.util.List;
import java.util.Set;

/** Durable person -> location role assignments (D24 R-LR-6). */
public interface TrackedPersonStore {

    /** One person at one location with every role they hold there; familyProfileId is reserved for slice 2 and may be null. */
    record Assignment(String personId, String location, Set<LocationRole> roles, String familyProfileId) {}

    List<Assignment> findAll();

    Set<LocationRole> rolesOf(String personId, String location);

    /** Replaces the roles of one person at one location; an empty set removes the assignment. */
    void setRoles(String personId, String location, Set<LocationRole> roles);
}
