package com.cabin.orchestrator.security;

import java.time.Instant;

/** Summary of a link's use: when it was first and last seen and how many visits ("opens") it has had. */
public record AccessUsage(Instant firstUsedAt, Instant lastUsedAt, long useCount) {
    public static final AccessUsage NONE = new AccessUsage(null, null, 0);
}
