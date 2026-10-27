package com.cabin.orchestrator.roofspec;

import java.time.Instant;

/**
 * One comment or requested input on the Roof Monitoring page. {@code author}
 * is server-derived (the share link's label for a guest, the signed-in email
 * otherwise) and never taken from the request body, so a guest cannot post
 * as someone else. {@code guestTokenId} ties a guest entry to its link for
 * the per-link cap; it is not returned to clients.
 */
public record RoofSpecEntry(
    String id,
    String kind,        // comment | input
    String itemKey,     // nullable; one of RoofSpecService.ITEM_KEYS
    String body,
    String author,
    String authorKind,  // guest | owner
    String guestTokenId,
    Instant createdAt,
    Instant deletedAt
) {}
