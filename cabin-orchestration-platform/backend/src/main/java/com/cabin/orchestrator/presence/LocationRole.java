package com.cabin.orchestrator.presence;

/**
 * D24: a person's relationship to ONE location. Separate from HouseholdRole (what a signed-in principal may do): a location
 * role never grants or removes a permission. PRIMARY is an active user of the location, whose phone counts as presence;
 * MAINTENANCE establishes and maintains it and is a guest for presence. One person can hold both at one location.
 */
public enum LocationRole {
    PRIMARY,
    MAINTENANCE
}
