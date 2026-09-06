package com.cabin.orchestrator.integrations.ring;

import com.cabin.orchestrator.platformimport.PlatformImportProvider;
import com.cabin.orchestrator.platformimport.RawImportRecord;
import com.cabin.orchestrator.security.OAuthCredential;
import com.cabin.orchestrator.security.OAuthCredentialStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * WSJF #9. Ring has no official public API and no clean capability manifest
 * like SmartThings -- `kind` is a hint, not a guarantee, so
 * measurementTypeCandidates here can legitimately be broader/less certain.
 * Vault entry name: "ring_oauth" (access_token + refresh_token + hardware_id
 * together -- see OAuthCredential.extra for why hardware_id rides alongside
 * the token pair instead of a separate storage call).
 *
 * This adapter deliberately does NOT implement Ring's own username/password
 * login flow -- it assumes a valid token pair + hardware_id already exist in
 * Vaultwarden. One-time manual setup (no code in this repo does this yet):
 *   1. Generate a random UUID, use it as hardware_id for every future call.
 *   2. POST https://oauth.ring.com/oauth/token
 *      { "grant_type": "password", "username": "...", "password": "...",
 *        "hardware_id": "<uuid>", "scope": "client" }
 *   3. If the response demands 2FA, retry per Ring's own (undocumented,
 *      community-established) verification-code flow.
 *   4. Store the resulting access_token + refresh_token + hardware_id via
 *      OAuthCredentialStore.store(VAULT_ENTRY_NAME, ...) once that store is
 *      actually wired to Vaultwarden (see VaultwardenOAuthCredentialStore's
 *      own javadoc -- not yet).
 */
@Component
public class RingImportProvider implements PlatformImportProvider {

    public static final String VAULT_ENTRY_NAME = "ring_oauth";
    private static final String DEVICES_URL = "https://api.ring.com/clients_api/ring_devices";
    private static final String TOKEN_URL = "https://oauth.ring.com/oauth/token";
    // Ring's own native-app client_id -- a public identifier for Ring's
    // Android app, not a secret (the community-established password-grant
    // flow this class's own javadoc documents already assumes it; no
    // client_secret exists for this flow at all).
    private static final String CLIENT_ID = "ring_official_android";
    private static final List<String> DEVICE_CATEGORIES = List.of("doorbells", "authorized_doorbells", "chimes", "other");

    private final OAuthCredentialStore credentialStore;
    private final RestTemplate http = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public RingImportProvider(OAuthCredentialStore credentialStore) {
        this.credentialStore = credentialStore;
    }

    @Override
    public String platform() {
        return "ring";
    }

    @Override
    public List<RawImportRecord> listDevices() {
        OAuthCredential credential = credentialStore.retrieve(VAULT_ENTRY_NAME)
            .orElseThrow(() -> new IllegalStateException(
                "No Ring OAuth credential in Vaultwarden (" + VAULT_ENTRY_NAME + ") -- complete the one-time setup first, see this class's javadoc"));
        credential = ensureFresh(credential);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(credential.accessToken());
        String body = http.exchange(DEVICES_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class).getBody();
        return parseDevices(body);
    }

    /** Sprint 5 WSJF #3. Same "check before every use" shape as SmartThingsImportProvider's own ensureFresh(). */
    private OAuthCredential ensureFresh(OAuthCredential credential) {
        if (!credential.isExpired()) return credential;
        if (credential.refreshToken() == null) {
            throw new IllegalStateException(
                "Ring credential expired and has no refresh token -- re-authorize and re-store " + VAULT_ENTRY_NAME);
        }
        OAuthCredential refreshed = refresh(credential);
        credentialStore.store(VAULT_ENTRY_NAME, refreshed);
        return refreshed;
    }

    /**
     * Live HTTP call -- thin wrapper around the pure parseRefreshResponse()
     * below. hardware_id must ride along on a refresh exactly as it does on
     * the original password-grant login (see this class's own javadoc:
     * "Ring rejects a refresh from an unrecognized hardware_id") -- sourced
     * from OAuthCredential.extra(), never re-generated here.
     */
    private OAuthCredential refresh(OAuthCredential credential) {
        String hardwareId = credential.extra() == null ? null : credential.extra().get("hardware_id");
        if (hardwareId == null) {
            throw new IllegalStateException(
                "Ring credential has no hardware_id in its extra map -- cannot refresh, re-authorize and re-store " + VAULT_ENTRY_NAME);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", credential.refreshToken());
        form.add("client_id", CLIENT_ID);
        form.add("hardware_id", hardwareId);
        form.add("scope", "client");
        String body = http.exchange(TOKEN_URL, HttpMethod.POST, new HttpEntity<>(form, headers), String.class).getBody();
        return parseRefreshResponse(body, credential);
    }

    /**
     * Pure, fixture-testable: turns a raw POST .../oauth/token refresh
     * response into a new OAuthCredential. extra() (hardware_id) always
     * carries over unchanged -- Ring's refresh response doesn't return it.
     */
    public OAuthCredential parseRefreshResponse(String responseBody, OAuthCredential previous) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            String accessToken = root.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new IllegalStateException("Ring token refresh response had no access_token");
            }
            String refreshToken = root.hasNonNull("refresh_token") ? root.path("refresh_token").asText() : previous.refreshToken();
            Instant expiresAt = root.hasNonNull("expires_in") ? Instant.now().plusSeconds(root.path("expires_in").asLong()) : null;
            return new OAuthCredential(accessToken, refreshToken, expiresAt, previous.extra());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Ring token refresh response", e);
        }
    }

    /** Pure, fixture-testable: turns a raw GET .../ring_devices response body into RawImportRecords. */
    public List<RawImportRecord> parseDevices(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            List<RawImportRecord> records = new ArrayList<>();
            for (String category : DEVICE_CATEGORIES) {
                for (JsonNode item : root.path(category)) {
                    String id = item.path("id").asText(null);
                    String name = item.path("description").path("name").asText(null);
                    String address = item.path("address").asText(null);
                    String kind = item.path("kind").asText("");
                    List<String> measurementTypes = kindMeasurementTypes(kind);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> rawPayload = mapper.convertValue(item, Map.class);
                    records.add(new RawImportRecord("ring", id, name, address, measurementTypes, rawPayload));
                }
            }
            return records;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Ring device list response", e);
        }
    }

    /** kind is a hint, never a guarantee -- surface every plausible candidate rather than silently picking one (see this class's own javadoc). */
    private static List<String> kindMeasurementTypes(String kind) {
        if (kind.startsWith("doorbell")) return List.of("motion", "contact");
        if (kind.startsWith("cam")) return List.of("motion");
        if (kind.startsWith("alarm")) return List.of("motion", "co", "leak");
        return List.of();
    }
}
