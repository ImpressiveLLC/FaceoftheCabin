package com.cabin.orchestrator.locations;

/**
 * Server-side counterpart to what cabin-ui's App.jsx has hardcoded as a
 * `const LOCATIONS = { cabin: {...}, home: {...} }` object since this
 * project's first session — this record mirrors that shape field-for-field
 * so an existing frontend build's env-var-driven defaults and this table's
 * seed data describe the same two locations without drift.
 *
 * Adding a new location (a third property, or a fork per REPLICATION.md)
 * becomes a CRUD write against this instead of a source edit and a
 * redeploy — see docs/EXECUTION_PLAN_2026-08-07_template-theme-camera.md
 * §1b.
 */
public record HubLocation(
    String id,
    String label,
    String apiBase,
    String wsBase,
    String grafanaUrl,
    String noderedUrl,
    String haUrl,
    String frigateUrl,
    String z2mUrl,
    String familyHubUrl,
    int sortOrder,
    boolean active,
    long createdAt,
    long updatedAt
) {}
