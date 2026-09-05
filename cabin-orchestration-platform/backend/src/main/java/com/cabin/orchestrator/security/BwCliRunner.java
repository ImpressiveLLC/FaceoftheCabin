package com.cabin.orchestrator.security;

import java.util.List;
import java.util.Map;

/**
 * Seam between VaultwardenOAuthCredentialStore and the actual `bw` (Bitwarden
 * CLI) subprocess, so tests can verify the exact command sequence and inject
 * canned responses without a real Vaultwarden instance or the `bw` binary
 * itself present. See VaultwardenOAuthCredentialStore's own javadoc for why a
 * CLI subprocess is used at all instead of a raw REST client: cipher content
 * is end-to-end encrypted client-side, and only Bitwarden's own maintained
 * crypto (which this CLI wraps) can correctly decrypt it -- an Organization
 * API key's client_credentials grant only bypasses 2FA for authentication,
 * it does not hand back decrypted vault data.
 */
public interface BwCliRunner {
    CliResult run(List<String> args, Map<String, String> extraEnv, String stdin);

    /** stdout/stderr are always trimmed -- `bw`'s own output reliably carries meaningful trailing newlines to strip, never meaningful trailing whitespace to preserve. */
    record CliResult(int exitCode, String stdout, String stderr) {
        boolean ok() { return exitCode == 0; }
    }
}
