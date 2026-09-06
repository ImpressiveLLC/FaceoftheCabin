package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 5 WSJF #3: the check both platform adapters' ensureFresh() relies on before every use. */
class OAuthCredentialTest {

    @Test
    void isExpiredIsFalseWhenExpiresAtIsNull() {
        OAuthCredential credential = new OAuthCredential("token", "refresh", null, Map.of());

        assertFalse(credential.isExpired(), "a platform that documents no expiry must never be guessed-expired");
    }

    @Test
    void isExpiredIsFalseWhenExpiresAtIsInTheFuture() {
        OAuthCredential credential = new OAuthCredential("token", "refresh", Instant.now().plus(Duration.ofHours(1)), Map.of());

        assertFalse(credential.isExpired());
    }

    @Test
    void isExpiredIsTrueWhenExpiresAtIsInThePast() {
        OAuthCredential credential = new OAuthCredential("token", "refresh", Instant.now().minus(Duration.ofMinutes(1)), Map.of());

        assertTrue(credential.isExpired());
    }
}
