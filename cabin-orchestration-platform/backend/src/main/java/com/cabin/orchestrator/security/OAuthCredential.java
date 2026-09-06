package com.cabin.orchestrator.security;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;
import java.util.Map;

/**
 * A stored OAuth credential pair (or a proprietary equivalent, e.g. Ring's
 * password-grant tokens). expiresAt is null when the platform doesn't
 * document one. extra carries platform-specific fields that aren't a token
 * at all -- Ring's hardware_id must travel with its token pair (Ring
 * rejects a refresh from an unrecognized hardware_id), so it rides here
 * rather than a second, disconnected storage call.
 */
public record OAuthCredential(String accessToken, String refreshToken, Instant expiresAt, Map<String, String> extra) {

    /**
     * False when expiresAt is null -- a platform that documents no expiry
     * (or one WSJF #9 never learned an expiry for) is treated as always
     * fresh rather than guessed-expired. Sprint 5 WSJF #3's own refresh
     * logic checks this before every use, not on a timer.
     *
     * @JsonIgnore is load-bearing, not decoration: Jackson's default record
     * handling treats any isXxx()/getXxx() method as an extra serializable
     * "expired" property alongside the four real components, which
     * VaultwardenOAuthCredentialStoreTest's own strict round-trip test
     * caught immediately -- a stored credential serialized with this
     * derived field present fails to deserialize back (UnrecognizedProperty
     * -- there's no matching constructor parameter). Found via that real
     * test failure, not guessed ahead of time.
     */
    @JsonIgnore
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }
}
