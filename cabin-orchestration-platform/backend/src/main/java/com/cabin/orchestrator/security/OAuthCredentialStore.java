package com.cabin.orchestrator.security;

import java.util.Optional;

/**
 * WSJF #9's non-negotiable constraint: every OAuth token this platform ever
 * holds (SmartThings, Ring, and any future platform adapter) goes through
 * this one seam -- never a raw token in .env, the app database, or a log
 * line. vaultEntryName matches a CREDENTIAL_POINTER KnowledgeNode's content
 * (e.g. "smartthings_oauth") -- the pointer (in the KB) and the actual
 * value (in Vaultwarden) are deliberately resolved through two different
 * systems. See VaultwardenOAuthCredentialStore for this interface's only
 * implementation and why its body isn't live yet.
 */
public interface OAuthCredentialStore {
    void store(String vaultEntryName, OAuthCredential credential);
    Optional<OAuthCredential> retrieve(String vaultEntryName);
}
