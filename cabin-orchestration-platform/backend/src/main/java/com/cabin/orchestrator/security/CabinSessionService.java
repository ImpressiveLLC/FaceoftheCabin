package com.cabin.orchestrator.security;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 2026-09-04 -- see CabinSession's own doc for why this exists. A real
 * Google sign-in exchanges its short-lived access token for one of these
 * (AuthController), then every subsequent request that carries a still-
 * valid one slides the 30-day window forward again -- "signed in within
 * the last 30 days stays recognized" is enforced by touching expiresAt on
 * every successful use, not just at issuance.
 */
@Service
public class CabinSessionService {

    private static final Duration SESSION_TTL = Duration.ofDays(30);

    private final CabinSessionStore sessions;

    public CabinSessionService(CabinSessionStore sessions) {
        this.sessions = sessions;
    }

    /** Always issues a brand-new token rather than reusing an old one -- matches ManagedUserSession's own "new session per successful auth" pattern. */
    public CabinSession issue(String googleEmail) {
        Instant now = Instant.now();
        CabinSession session = new CabinSession(UUID.randomUUID().toString(), googleEmail, now.plus(SESSION_TTL), null, now);
        sessions.save(session);
        return session;
    }

    /**
     * Empty unless the token is genuinely active right now. On success,
     * pushes expiresAt another 30 days out from THIS moment -- the sliding
     * part of the sliding window. Returns the verified email, not the raw
     * session, since that's all callers actually need.
     */
    public Optional<String> validateAndExtend(String rawToken) {
        Instant now = Instant.now();
        Optional<CabinSession> sessionOpt = sessions.findByToken(rawToken).filter(s -> s.isValid(now));
        if (sessionOpt.isEmpty()) return Optional.empty();
        sessions.extend(rawToken, now.plus(SESSION_TTL));
        return Optional.of(sessionOpt.get().googleEmail());
    }

    public void revoke(String rawToken) {
        sessions.revoke(rawToken, Instant.now());
    }
}
