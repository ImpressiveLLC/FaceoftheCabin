package com.cabin.orchestrator.security;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * WSJF #9 -- this class exists so OAuthCredentialStore has a real Spring
 * bean, but its body deliberately does NOT talk to Vaultwarden yet.
 *
 * WSJF #8 shipped Vaultwarden as a bare container with zero setup: no
 * Organization, no API key, nothing an HTTP client could authenticate
 * against -- and its own scope note said as much: "the actual secret
 * retrieval flow (backend -> Vaultwarden API -> service) is post-Sprint-3."
 * This item's own handover assumes that flow already exists. It doesn't,
 * and writing a Bitwarden-API client now, unverified against a real
 * Vaultwarden instance (nothing in this session has network access to the
 * M920q to confirm request/response shapes against), risks a worse outcome
 * than being explicit about the gap -- a subtly wrong implementation could
 * silently mishandle a live OAuth credential. This fails loudly instead,
 * the same convention BlinkMotionWebhookController/TechIdFindingService
 * already use for "not configured yet" (503, never a silent no-op).
 *
 * To finish this:
 *   1. Open Vaultwarden's web vault (http://<tailscale-ip>:8222, per #8),
 *      create the first account, then create an Organization.
 *   2. Settings -> My Organization -> API Key -> generate one (client_id +
 *      client_secret).
 *   3. Add vault_vaultwarden_org_client_id / vault_vaultwarden_org_client_secret
 *      to the vault (ansible/group_vars/cabin/vault.yml), wire them through
 *      vars.yml/env.j2 the same way every other credential here is.
 *   4. Implement store()/retrieve() against Vaultwarden's Bitwarden-compatible
 *      /identity/connect/token (client_credentials grant) + /api/ciphers
 *      endpoints, storing/reading a Secure Note cipher named vaultEntryName.
 */
@Component
public class VaultwardenOAuthCredentialStore implements OAuthCredentialStore {

    @Override
    public void store(String vaultEntryName, OAuthCredential credential) {
        throw new IllegalStateException(
            "Vaultwarden API wiring is not yet implemented -- see this class's own javadoc for the setup needed "
                + "before it can be built. Cannot store " + vaultEntryName + ".");
    }

    @Override
    public Optional<OAuthCredential> retrieve(String vaultEntryName) {
        throw new IllegalStateException(
            "Vaultwarden API wiring is not yet implemented -- see this class's own javadoc. Cannot retrieve " + vaultEntryName + ".");
    }
}
