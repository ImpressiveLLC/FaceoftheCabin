package com.cabin.orchestrator.security;

import java.time.Instant;

/**
 * Tier 2 "Managed Users" -- see the plan's "Guest Access Model" section.
 * For a trusted household member or recurring non-Google collaborator who
 * needs standing access without Google credentials, unlike a Tier 1 guest
 * link (anonymous, shareable, admin-issued for one external party at a
 * time). Admin-managed enrollment ("Nate controls who's on the list"),
 * but each managed user's session is genuinely their own once they've
 * clicked their own magic link -- never a proxy of the admin's session.
 *
 * active lets an admin suspend access without deleting the row (matching
 * this project's non-destructive-by-default convention -- see the
 * IGNORE-not-DELETE lifecycle discipline built for devices) -- a suspended
 * user's existing sessions are also checked against this flag, not just
 * blocked from requesting a new magic link.
 */
public record ManagedUser(
    String id,
    String email,
    String name,
    ManagedUserRole role,
    boolean active,
    String createdBy,
    Instant createdAt
) {}
