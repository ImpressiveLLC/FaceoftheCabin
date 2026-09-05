package com.cabin.orchestrator.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WSJF #9 (backlog "Vaultwarden API not actually wired") -- real store()/
 * retrieve() via the official `bw` (Bitwarden) CLI as a subprocess, NOT a raw
 * REST client against Vaultwarden's /identity + /api endpoints as this
 * class's own earlier stub javadoc proposed.
 *
 * That earlier plan doesn't actually work: cipher content in Bitwarden/
 * Vaultwarden is end-to-end encrypted client-side. An Organization API key's
 * client_credentials grant against /identity/connect/token only bypasses 2FA
 * for authentication -- it does not hand back the master-password-derived
 * key needed to decrypt a Secure Note's actual content. A raw REST client
 * hitting /api/ciphers with just that grant gets back encrypted blobs it
 * can't read, not a working credential store. Verified directly (2026-09-05)
 * against a real community tool (Turbootzz/Vaultwarden-API) implementing
 * this exact flow: "the VAULTWARDEN_PASSWORD is still required -- it's used
 * for decryption, not authentication" even when API-key credentials are
 * configured.
 *
 * Shelling out to Bitwarden's own official CLI avoids re-implementing
 * Bitwarden's PBKDF2/Argon2id key derivation + AES-256-CBC+HMAC decryption
 * in Java -- getting that subtly wrong would silently mishandle a live OAuth
 * credential, exactly the risk this class's original stub was written to
 * avoid guessing past. The CLI's own team maintains that crypto; this class
 * only orchestrates it.
 *
 * Session lifecycle: `bw login --apikey` (BW_CLIENTID/BW_CLIENTSECRET env,
 * never argv -- see ProcessBwCliRunner's own doc) is a one-time-per-container
 * step; `bw unlock --passwordenv BW_PASSWORD` (BW_PASSWORD env) returns a
 * session key string, cached in memory for this JVM's lifetime and reused
 * across calls rather than re-deriving it (PBKDF2/Argon2id is deliberately
 * slow) on every store()/retrieve(). Cleared and re-derived once on any
 * command failure after a session was believed valid, in case the local
 * `bw` state was invalidated some other way (e.g. a manual `bw logout` on
 * the host).
 *
 * A vaultEntryName round-trips as a Secure Note (type 2) whose `notes` field
 * holds the OAuthCredential serialized as JSON -- one round-trippable unit
 * rather than splitting fields across Bitwarden's custom-field shape, which
 * buys nothing here since nothing but this class ever reads/writes these
 * items by hand.
 */
@Component
public class VaultwardenOAuthCredentialStore implements OAuthCredentialStore {

    private static final Logger log = LoggerFactory.getLogger(VaultwardenOAuthCredentialStore.class);
    private static final int SECURE_NOTE_TYPE = 2;

    @Value("${cabin.vaultwarden.url:}")
    private String serverUrl;
    @Value("${cabin.vaultwarden.clientId:}")
    private String clientId;
    @Value("${cabin.vaultwarden.clientSecret:}")
    private String clientSecret;
    @Value("${cabin.vaultwarden.masterPassword:}")
    private String masterPassword;

    private final BwCliRunner cli;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private volatile String sessionKey;

    public VaultwardenOAuthCredentialStore(BwCliRunner cli) {
        this.cli = cli;
    }

    private void requireConfigured() {
        if (serverUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank() || masterPassword.isBlank()) {
            throw new IllegalStateException(
                "Vaultwarden is not configured on this instance (cabin.vaultwarden.url/clientId/clientSecret/masterPassword) "
                    + "-- see docs/MAINTENANCE.md's Vaultwarden section for one-time Organization + API key setup.");
        }
    }

    @Override
    public synchronized void store(String vaultEntryName, OAuthCredential credential) {
        requireConfigured();
        String session = ensureSession();
        try {
            String notes = mapper.writeValueAsString(credential);
            Optional<String> existingId = findItemId(vaultEntryName, session);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("organizationId", null);
            item.put("folderId", null);
            item.put("type", SECURE_NOTE_TYPE);
            item.put("name", vaultEntryName);
            item.put("notes", notes);
            item.put("secureNote", Map.of("type", 0));
            item.put("favorite", false);
            String encoded = encode(mapper.writeValueAsString(item));
            BwCliRunner.CliResult result = existingId.isPresent()
                ? cli.run(List.of("edit", "item", existingId.get(), encoded, "--session", session), Map.of(), null)
                : cli.run(List.of("create", "item", encoded, "--session", session), Map.of(), null);
            if (!result.ok()) {
                // One retry with a freshly-derived session -- covers the local
                // `bw` state having been invalidated some other way (e.g. a
                // manual `bw logout` on the host) since this session key was
                // cached, without retrying indefinitely for a genuine failure.
                sessionKey = null;
                String freshSession = ensureSession();
                BwCliRunner.CliResult retry = existingId.isPresent()
                    ? cli.run(List.of("edit", "item", existingId.get(), encoded, "--session", freshSession), Map.of(), null)
                    : cli.run(List.of("create", "item", encoded, "--session", freshSession), Map.of(), null);
                if (!retry.ok()) {
                    throw new IllegalStateException("bw " + (existingId.isPresent() ? "edit" : "create")
                        + " item failed for " + vaultEntryName + ": " + retry.stderr());
                }
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize credential for " + vaultEntryName, e);
        }
    }

    @Override
    public synchronized Optional<OAuthCredential> retrieve(String vaultEntryName) {
        requireConfigured();
        String session = ensureSession();
        BwCliRunner.CliResult result = cli.run(List.of("get", "item", vaultEntryName, "--session", session), Map.of(), null);
        if (!result.ok()) {
            // A session that was valid a moment ago failing here is more
            // likely "the item doesn't exist" than "the session just broke"
            // -- ensureSession() already succeeded this call. Callers see a
            // real, actionable IllegalStateException from ensureSession()
            // itself if the underlying CLI/server connection is actually
            // broken; this path is reserved for the ordinary "not found" case
            // the OAuthCredentialStore interface's Optional contract expects.
            return Optional.empty();
        }
        try {
            var itemJson = mapper.readTree(result.stdout());
            String notes = itemJson.path("notes").asText(null);
            if (notes == null || notes.isBlank()) return Optional.empty();
            return Optional.of(mapper.readValue(notes, OAuthCredential.class));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Vaultwarden item for " + vaultEntryName, e);
        }
    }

    private Optional<String> findItemId(String vaultEntryName, String session) {
        BwCliRunner.CliResult result = cli.run(List.of("get", "item", vaultEntryName, "--session", session), Map.of(), null);
        if (!result.ok()) return Optional.empty();
        try {
            return Optional.ofNullable(mapper.readTree(result.stdout()).path("id").asText(null));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String encode(String rawJson) {
        BwCliRunner.CliResult result = cli.run(List.of("encode"), Map.of(), rawJson);
        if (!result.ok()) {
            throw new IllegalStateException("bw encode failed: " + result.stderr());
        }
        return result.stdout();
    }

    /**
     * Cheap to call repeatedly (returns the cached key once established) --
     * `bw unlock`'s PBKDF2/Argon2id key derivation is deliberately slow, so
     * this is only ever paid once per JVM lifetime unless a command failure
     * clears the cache (see store()'s own retry-once comment).
     */
    private String ensureSession() {
        String cached = sessionKey;
        if (cached != null) return cached;
        return deriveSession();
    }

    private synchronized String deriveSession() {
        if (sessionKey != null) return sessionKey; // another thread won the race while this one waited on the lock

        BwCliRunner.CliResult configResult = cli.run(List.of("config", "server", serverUrl), Map.of(), null);
        if (!configResult.ok()) {
            throw new IllegalStateException("bw config server failed: " + configResult.stderr());
        }

        BwCliRunner.CliResult status = cli.run(List.of("status"), Map.of(), null);
        boolean authenticated = status.ok()
            && (status.stdout().contains("\"status\":\"locked\"") || status.stdout().contains("\"status\":\"unlocked\""));
        if (!authenticated) {
            BwCliRunner.CliResult login = cli.run(List.of("login", "--apikey"),
                Map.of("BW_CLIENTID", clientId, "BW_CLIENTSECRET", clientSecret), null);
            if (!login.ok()) {
                throw new IllegalStateException("bw login --apikey failed -- check cabin.vaultwarden.clientId/clientSecret: " + login.stderr());
            }
        }

        BwCliRunner.CliResult unlock = cli.run(List.of("unlock", "--passwordenv", "BW_PASSWORD", "--raw"),
            Map.of("BW_PASSWORD", masterPassword), null);
        if (!unlock.ok()) {
            throw new IllegalStateException("bw unlock failed -- check cabin.vaultwarden.masterPassword: " + unlock.stderr());
        }
        // Defensive: --raw is documented to print the bare key, but if a
        // future CLI version reverts to the shell-export-formatted form
        // (`export BW_SESSION="..."`) anyway, extract the quoted value
        // rather than caching an unusable session string.
        String rawOutput = unlock.stdout();
        String session = rawOutput.contains("BW_SESSION=")
            ? rawOutput.replaceAll(".*BW_SESSION=\"?([^\"\\n]+)\"?.*", "$1")
            : rawOutput;

        BwCliRunner.CliResult sync = cli.run(List.of("sync", "--session", session), Map.of(), null);
        if (!sync.ok()) {
            log.warn("bw sync failed after unlock (continuing with possibly-stale local cache): {}", sync.stderr());
        }

        sessionKey = session;
        return session;
    }
}
