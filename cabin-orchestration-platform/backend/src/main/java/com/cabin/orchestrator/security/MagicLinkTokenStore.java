package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.Optional;

public interface MagicLinkTokenStore {
    void save(MagicLinkToken token);
    Optional<MagicLinkToken> findByToken(String token);
    /** Marks the token consumed only if it is still unused; true for the one caller that did it, false for every other (atomic). */
    boolean consumeIfUnused(String token, Instant consumedAt);
}
