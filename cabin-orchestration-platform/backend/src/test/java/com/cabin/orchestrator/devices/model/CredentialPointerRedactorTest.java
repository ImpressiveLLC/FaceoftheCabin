package com.cabin.orchestrator.devices.model;

import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CredentialPointerRedactorTest {

    private static KnowledgeNode credentialPointer(String content) {
        return new KnowledgeNode("Resend", KnowledgeChunkType.CREDENTIAL_POINTER, content, KnowledgeSource.MANUALLY_CURATED, Instant.now());
    }

    @Test
    void administratorGetsTheRealVaultEntryName() {
        KnowledgeNode redacted = CredentialPointerRedactor.redact(credentialPointer("vault_resend_api_key"), HouseholdRole.ADMINISTRATOR);

        assertTrue(redacted.content().contains("vault_resend_api_key"));
        assertTrue(redacted.content().contains("Vaultwarden"));
    }

    @Test
    void nonAdministratorNeverSeesTheVaultEntryName() {
        KnowledgeNode redacted = CredentialPointerRedactor.redact(credentialPointer("vault_resend_api_key"), HouseholdRole.ADULT_HOUSEHOLD_MEMBER);

        assertFalse(redacted.content().contains("vault_"));
        assertEquals("Contact an administrator for this credential.", redacted.content());
    }

    @Test
    void aNullRoleFailsClosedLikeAnyNonAdministrator() {
        KnowledgeNode redacted = CredentialPointerRedactor.redact(credentialPointer("vault_resend_api_key"), null);

        assertEquals("Contact an administrator for this credential.", redacted.content());
    }

    @Test
    void everyOtherChunkTypeIsUntouchedRegardlessOfRole() {
        KnowledgeNode description = new KnowledgeNode("z2m-temp_kitchen", KnowledgeChunkType.DESCRIPTION,
            "temp_kitchen is a SONOFF sensor.", KnowledgeSource.AUTO_GENERATED, Instant.now());

        KnowledgeNode redacted = CredentialPointerRedactor.redact(description, null);

        assertSame(description, redacted, "only CREDENTIAL_POINTER content is ever rewritten");
    }
}
