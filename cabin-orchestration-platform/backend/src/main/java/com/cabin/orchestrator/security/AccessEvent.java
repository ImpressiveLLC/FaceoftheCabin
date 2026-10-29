package com.cabin.orchestrator.security;

import java.time.Instant;

/**
 * One row of an access link's usage trail. {@code tokenId} is the link's id, never the bearer token.
 * Repeated requests on the same route and status within one 5-minute bucket are collapsed into one
 * row whose {@code hits} counts them, so a polling UI does not flood the table.
 */
public record AccessEvent(
    long id,
    String tokenId,
    Instant occurredAt,   // latest request in the bucket
    String tier,          // "view" (Tier 1 guest link) or "demo"
    String route,         // request path, or "page_open" for the demo page-load call
    String policyClass,   // ALLOW / ALLOW_REDACT for demo, DENY / WRITE / INACTIVE for refusals, GUEST for Tier 1
    int status,
    String clientIp,
    String userAgent,
    int hits
) {
    public static final String TIER_VIEW = "view";
    public static final String TIER_DEMO = "demo";
    public static final String ROUTE_PAGE_OPEN = "page_open";
}
