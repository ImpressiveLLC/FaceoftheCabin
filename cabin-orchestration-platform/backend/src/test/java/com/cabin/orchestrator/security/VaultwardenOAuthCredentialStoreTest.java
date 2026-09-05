package com.cabin.orchestrator.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.cabin.orchestrator.security.BwCliRunner.CliResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WSJF #9's real completion (2026-09-05) -- exercises the actual `bw` CLI
 * orchestration via a FakeBwCliRunner, since a real Vaultwarden instance +
 * `bw` binary isn't available in this test environment (and shouldn't need
 * to be -- see VaultwardenOAuthCredentialStore's own javadoc for why a
 * subprocess wraps Bitwarden's own crypto rather than this class attempting
 * it directly). Verifies the exact command sequence and that secrets travel
 * via env, never argv -- both real, checkable properties, not just "it
 * returns the right value."
 */
class VaultwardenOAuthCredentialStoreTest {

    /** Records every invocation; dispatches a canned response by the command's first token. */
    static class FakeBwCliRunner implements BwCliRunner {
        final List<List<String>> calls = new ArrayList<>();
        final List<Map<String, String>> envs = new ArrayList<>();
        final Map<String, CliResult> responses = new HashMap<>();

        @Override
        public CliResult run(List<String> args, Map<String, String> extraEnv, String stdin) {
            calls.add(args);
            envs.add(extraEnv);
            return responses.getOrDefault(args.get(0), new CliResult(0, "", ""));
        }
    }

    private FakeBwCliRunner fake;
    private VaultwardenOAuthCredentialStore store;

    @BeforeEach
    void setUp() {
        fake = new FakeBwCliRunner();
        // Default happy-path responses for the session-derivation sequence --
        // individual tests override only the ones they care about.
        fake.responses.put("status", new CliResult(0, "{\"status\":\"locked\"}", ""));
        fake.responses.put("unlock", new CliResult(0, "fake-session-key", ""));
        store = new VaultwardenOAuthCredentialStore(fake);
        ReflectionTestUtils.setField(store, "serverUrl", "http://vaultwarden.example:8222");
        ReflectionTestUtils.setField(store, "clientId", "user.abc123");
        ReflectionTestUtils.setField(store, "clientSecret", "supersecretvalue");
        ReflectionTestUtils.setField(store, "masterPassword", "correct horse battery staple");
    }

    @Test
    void unconfiguredInstanceFailsLoudlyRatherThanSilentlyNoOpping() {
        // Mirrors production's real default: application.yml's ${VAULTWARDEN_URL:}
        // style properties resolve to "" when unset, never null -- constructing
        // this store outside Spring (as this whole test class does) leaves
        // @Value fields at Java's own uninitialized-String default (null)
        // unless set explicitly, which would NPE instead of exercising the
        // real "operator hasn't set this up yet" production state.
        VaultwardenOAuthCredentialStore unconfigured = new VaultwardenOAuthCredentialStore(fake);
        ReflectionTestUtils.setField(unconfigured, "serverUrl", "");
        ReflectionTestUtils.setField(unconfigured, "clientId", "");
        ReflectionTestUtils.setField(unconfigured, "clientSecret", "");
        ReflectionTestUtils.setField(unconfigured, "masterPassword", "");
        OAuthCredential credential = new OAuthCredential("token", "refresh", Instant.now(), Map.of());

        assertThrows(IllegalStateException.class, () -> unconfigured.store("smartthings_oauth", credential));
        assertThrows(IllegalStateException.class, () -> unconfigured.retrieve("smartthings_oauth"));
        assertTrue(fake.calls.isEmpty(), "must fail before ever shelling out to bw at all");
    }

    @Test
    void sessionDerivationSkipsLoginWhenAlreadyAuthenticated() {
        fake.responses.put("get", new CliResult(1, "", "Not found."));

        store.retrieve("smartthings_oauth");

        List<String> commands = fake.calls.stream().map(c -> c.get(0)).toList();
        assertEquals(List.of("config", "status", "unlock", "sync", "get"), commands,
            "status already 'locked' (not 'unauthenticated') must skip a redundant login --apikey call");
    }

    @Test
    void sessionDerivationLogsInFirstWhenUnauthenticated() {
        fake.responses.put("status", new CliResult(0, "{\"status\":\"unauthenticated\"}", ""));
        fake.responses.put("get", new CliResult(1, "", "Not found."));

        store.retrieve("smartthings_oauth");

        List<String> commands = fake.calls.stream().map(c -> c.get(0)).toList();
        assertEquals(List.of("config", "status", "login", "unlock", "sync", "get"), commands);
    }

    @Test
    void secretsTravelViaEnvironmentNeverAsCommandLineArguments() {
        fake.responses.put("status", new CliResult(0, "{\"status\":\"unauthenticated\"}", ""));
        fake.responses.put("get", new CliResult(1, "", "Not found."));

        store.retrieve("smartthings_oauth");

        for (List<String> call : fake.calls) {
            assertFalse(call.contains("supersecretvalue"), "client secret must never appear in argv: " + call);
            assertFalse(call.contains("correct horse battery staple"), "master password must never appear in argv: " + call);
        }
        Map<String, String> loginEnv = fake.envs.get(fake.calls.stream().map(c -> c.get(0)).toList().indexOf("login"));
        assertEquals("user.abc123", loginEnv.get("BW_CLIENTID"));
        assertEquals("supersecretvalue", loginEnv.get("BW_CLIENTSECRET"));
        Map<String, String> unlockEnv = fake.envs.get(fake.calls.stream().map(c -> c.get(0)).toList().indexOf("unlock"));
        assertEquals("correct horse battery staple", unlockEnv.get("BW_PASSWORD"));
    }

    @Test
    void sessionIsDerivedOnceAndCachedAcrossMultipleCalls() {
        fake.responses.put("get", new CliResult(1, "", "Not found."));

        store.retrieve("smartthings_oauth");
        store.retrieve("ring_oauth");

        long unlockCalls = fake.calls.stream().filter(c -> c.get(0).equals("unlock")).count();
        assertEquals(1, unlockCalls, "the deliberately-slow PBKDF2/Argon2id unlock must not repeat per call once cached");
    }

    @Test
    void retrieveReturnsEmptyWhenTheItemDoesNotExist() {
        fake.responses.put("get", new CliResult(1, "", "Not found."));

        assertTrue(store.retrieve("smartthings_oauth").isEmpty());
    }

    @Test
    void retrieveDeserializesTheCredentialFromTheNotesField() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        Instant expiresAt = Instant.parse("2026-10-01T00:00:00Z");
        String credentialJson = mapper.writeValueAsString(
            new OAuthCredential("tok-123", "ref-456", expiresAt, Map.of("scope", "r:devices:*")));
        String itemJson = mapper.writeValueAsString(Map.of(
            "id", "item-1", "name", "smartthings_oauth", "notes", credentialJson));
        fake.responses.put("get", new CliResult(0, itemJson, ""));

        OAuthCredential result = store.retrieve("smartthings_oauth").orElseThrow();

        assertEquals("tok-123", result.accessToken());
        assertEquals("ref-456", result.refreshToken());
        assertEquals(expiresAt, result.expiresAt());
        assertEquals("r:devices:*", result.extra().get("scope"));
    }

    @Test
    void storeCreatesANewItemWhenNoneExistsYet() {
        fake.responses.put("get", new CliResult(1, "", "Not found."));
        fake.responses.put("encode", new CliResult(0, "ZW5jb2RlZC1qc29u", ""));
        fake.responses.put("create", new CliResult(0, "{\"id\":\"new-item-1\"}", ""));

        store.store("smartthings_oauth", new OAuthCredential("tok", "ref", Instant.now(), Map.of()));

        List<String> createCall = fake.calls.stream().filter(c -> c.get(0).equals("create")).findFirst().orElseThrow();
        assertEquals(List.of("create", "item", "ZW5jb2RlZC1qc29u", "--session", "fake-session-key"), createCall);
        assertTrue(fake.calls.stream().noneMatch(c -> c.get(0).equals("edit")), "must not edit when nothing existed to edit");
    }

    @Test
    void storeEditsTheExistingItemInsteadOfCreatingADuplicate() {
        fake.responses.put("get", new CliResult(0, "{\"id\":\"existing-item-7\"}", ""));
        fake.responses.put("encode", new CliResult(0, "ZW5jb2RlZC1qc29u", ""));
        fake.responses.put("edit", new CliResult(0, "{\"id\":\"existing-item-7\"}", ""));

        store.store("smartthings_oauth", new OAuthCredential("tok", "ref", Instant.now(), Map.of()));

        List<String> editCall = fake.calls.stream().filter(c -> c.get(0).equals("edit")).findFirst().orElseThrow();
        assertEquals(List.of("edit", "item", "existing-item-7", "ZW5jb2RlZC1qc29u", "--session", "fake-session-key"), editCall);
        assertTrue(fake.calls.stream().noneMatch(c -> c.get(0).equals("create")), "must not create a duplicate when one already exists");
    }

    @Test
    void loginFailureSurfacesAClearActionableError() {
        fake.responses.put("status", new CliResult(0, "{\"status\":\"unauthenticated\"}", ""));
        fake.responses.put("login", new CliResult(1, "", "Client id or secret is invalid."));

        var ex = assertThrows(IllegalStateException.class, () -> store.retrieve("smartthings_oauth"));
        assertTrue(ex.getMessage().contains("clientId"), "should point at the config property to check, not just echo the CLI's own error");
    }

    @Test
    void unlockFailureSurfacesAClearActionableError() {
        fake.responses.put("unlock", new CliResult(1, "", "Invalid master password."));

        var ex = assertThrows(IllegalStateException.class, () -> store.retrieve("smartthings_oauth"));
        assertTrue(ex.getMessage().contains("masterPassword"));
    }

    @Test
    void aPersistentCreateFailureRetriesOnceWithAFreshSessionThenThrows() {
        fake.responses.put("get", new CliResult(1, "", "Not found."));
        fake.responses.put("encode", new CliResult(0, "ZW5jb2RlZC1qc29u", ""));
        fake.responses.put("create", new CliResult(1, "", "Server error."));

        assertThrows(IllegalStateException.class,
            () -> store.store("smartthings_oauth", new OAuthCredential("tok", "ref", Instant.now(), Map.of())));

        long unlockCalls = fake.calls.stream().filter(c -> c.get(0).equals("unlock")).count();
        long createCalls = fake.calls.stream().filter(c -> c.get(0).equals("create")).count();
        assertEquals(2, unlockCalls, "one retry means one fresh session derivation, not unbounded retries");
        assertEquals(2, createCalls);
    }
}
