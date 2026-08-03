package com.cabin.orchestrator.techid;

/**
 * One human interaction with an Opportunity (TechIdFinding), recorded
 * for the See/Think/Act model's own "opportunity analysis" -- the user's
 * explicit requirement that any action taken from the opportunity map be
 * logged, not just executed and forgotten. This is the same append-only,
 * immutable-audit-log pattern already established for device commands
 * (docs/PRODUCT_NOTES.md's 2026-08-01 Action Model Decision) applied to
 * the opportunity surface.
 *
 * actionType is one of:
 *   see_expand           -- opened "see more" (full research/sources)
 *   think_include        -- marked the opportunity worth pursuing
 *   think_dismiss        -- dismissed it (detail should carry a reason)
 *   act_purchase_elsewhere -- followed an external source/purchase link
 *   act_request_core     -- asked the platform operator to build this in
 *   act_do_it_now        -- used the finding's own actionable.detail/url
 *                            to self-serve with what's already installed
 *
 * Free text, not an enum, matching `provider` on TechIdFinding and
 * `findingType` -- new action types (e.g. a future in-UI purchase flow)
 * should never require a schema change here.
 */
public record TechIdFindingAction(
    String id,
    String findingId,
    String actionType,
    String actorEmail,
    String detail,
    long createdAt
) {}
