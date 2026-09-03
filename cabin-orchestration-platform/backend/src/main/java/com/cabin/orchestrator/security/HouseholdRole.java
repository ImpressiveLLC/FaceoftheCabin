package com.cabin.orchestrator.security;

/**
 * Server-derived household role -- see GoogleAuthInterceptor's role
 * derivation for how each auth path maps into this. Always computed
 * server-side from the already-verified principal (Google account email,
 * managed-user role) -- never trusted from anything client-supplied.
 * Matches the same "authentication and attribution are separate concerns"
 * line this app already draws for actorId (see that field's own
 * investigation, D11 pins): a role answers "what is this principal
 * allowed to do," never "what does the request body claim."
 *
 * CHILD, KIOSK_DISPLAY, and SERVICE exist as real values with no live
 * derivation path yet -- deliberately not built speculatively (D11's
 * authorization-model hard gate is about the mechanism, not a finished
 * feature). They exist so a per-route policy check can be written and
 * tested against them now; a future managed-user child enrollment or
 * kiosk session slots into the same enum without touching every existing
 * check that already reasons about roles.
 */
public enum HouseholdRole {
    ADMINISTRATOR,
    ADULT_HOUSEHOLD_MEMBER,
    CHILD,
    KIOSK_DISPLAY,
    SERVICE
}
