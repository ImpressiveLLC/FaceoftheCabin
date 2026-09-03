package com.cabin.orchestrator.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WSJF #9 -- pins the deliberate "fails loudly, not silently" behavior this
 * class's own javadoc describes: until the real Vaultwarden API wiring
 * exists, every call must throw, never silently no-op or return an empty
 * Optional that a caller could mistake for "no credential configured yet"
 * (a materially different, less severe state).
 */
class VaultwardenOAuthCredentialStoreTest {

    private final VaultwardenOAuthCredentialStore store = new VaultwardenOAuthCredentialStore();

    @Test
    void storeFailsLoudlyRatherThanSilentlyNoOpping() {
        OAuthCredential credential = new OAuthCredential("token", "refresh", Instant.now(), Map.of());

        assertThrows(IllegalStateException.class, () -> store.store("smartthings_oauth", credential));
    }

    @Test
    void retrieveFailsLoudlyRatherThanReturningAMisleadingEmptyOptional() {
        assertThrows(IllegalStateException.class, () -> store.retrieve("smartthings_oauth"));
    }
}
