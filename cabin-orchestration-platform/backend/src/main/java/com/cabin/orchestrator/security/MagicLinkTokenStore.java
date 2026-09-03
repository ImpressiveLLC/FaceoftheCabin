package com.cabin.orchestrator.security;

import java.time.Instant;
import java.util.Optional;

public interface MagicLinkTokenStore {
    void save(MagicLinkToken token);
    Optional<MagicLinkToken> findByToken(String token);
    void markConsumed(String token, Instant consumedAt);
}
