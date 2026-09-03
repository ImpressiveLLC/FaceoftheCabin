package com.cabin.orchestrator.security;

/**
 * Tier 2 managed user's access level -- see ManagedUser's own doc. Unlike a
 * Tier 1 guest link (scope-limited to 4 fixed read-only path prefixes, see
 * GoogleAuthInterceptor.SCOPE_PATH_PREFIXES), a managed user gets the same
 * broad read access a Google-signed-in family member gets; the only
 * distinction this role draws is whether writes are allowed too.
 */
public enum ManagedUserRole {
    /** Read-only, same blanket "never a write credential" rule Tier 1 guest links enforce. */
    VIEWER,
    /** Full read/write, same trust level as a signed-in Google account -- see actorId's own "same trust as a fridge note" model. */
    HOUSEHOLD_MEMBER;

    public boolean allowsWrite() {
        return this == HOUSEHOLD_MEMBER;
    }
}
