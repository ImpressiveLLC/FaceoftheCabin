package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W-117 C2 slice 3 (identity and credentials). Questions about becoming an administrator, sign-in sessions, guest link
 * checks and the public sign-in link reach identity_sessions; inventory and rotation questions reach credential_redaction
 * with credentials-and-access still first. Credential-location questions keep credentials-and-access (the #482 HOLD
 * precedent), and ordinary sharing questions keep their categories. The phrasings are written for this test from the
 * sections' own wording; none is a held-out or sealed-set question.
 */
class AskIdentityCredentialsRoutingTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();

    private AskContextBuilder builder;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.isDirectory(REPO_DOCS.resolve("ai-assistant/user-guide")),
            "repo docs/ not reachable from the test working directory");
        builder = new AskContextBuilder(new NoNodes(), new ReviewedDocumentSource(REPO_DOCS.toString()),
            new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    private static final String ADMIN = "#how-someone-becomes-an-administrator";
    private static final String SESSIONS = "#sign-in-sessions-google-30-day-sessions-and-managed-sessions";
    private static final String SIGN_IN_LINK = "#why-the-sign-in-link-opens-without-signing-in";
    private static final String GUEST_CHECKS = "#what-the-server-checks-when-a-guest-link-is-made";

    @Test
    void identityQuestionsReachTheirOwnCategory() {
        Map<String, String> pinned = Map.of(
            "How do I make someone an administrator?", "identity_admin",
            "What does ADMIN_EMAILS do?", "identity_admin",
            "How long does the 30-day session last and what ends it?", "identity_sessions",
            "Why does the managed sign-in link work without signing in?", "identity_sign_in_link",
            "What guest link scope does the server require?", "identity_guest_link_checks");
        pinned.forEach((question, category) -> assertThat(builder.categoryIdFor(question)).as(question).isEqualTo(category));
    }

    /**
     * Delivered content, not just the category: the asked-for section text reaches an administrator whole, inside the
     * 6000-character budget. The last question is the #490 review probe, which ties on "token" with credential_redaction
     * and used to lose the session section to the budget.
     */
    @Test
    void theAskedForSectionIsDeliveredWholeWithinTheBudget() {
        Map<String, String> wanted = Map.of(
            "How do I make someone an administrator?", ADMIN,
            "How long does the 30-day session last and what ends it?", SESSIONS,
            "Why does the managed sign-in link work without signing in?", SIGN_IN_LINK,
            "What guest link scope does the server require?", GUEST_CHECKS,
            "How long does my token keep me signed in for?", SESSIONS);
        wanted.forEach((question, section) -> {
            List<KnowledgeNode> nodes = builder.buildContext(question, HouseholdRole.ADMINISTRATOR);
            KnowledgeNode hit = nodes.stream().filter(n -> n.entityRef().endsWith(section)).findFirst().orElse(null);
            assertThat(hit).as(question + " delivers " + section).isNotNull();
            assertThat(hit.content()).as(question).doesNotContain("section truncated");
            assertThat(nodes.stream().mapToInt(n -> n.content().length()).sum()).as(question + " total").isLessThanOrEqualTo(6000);
        });
    }

    /**
     * The inventory is its own category so credential_redaction stays exactly as it was: a password or token question
     * that ties with a recovery category (#487, for example Node-RED password rotation) must still have budget left
     * for the recovery section.
     */
    @Test
    void inventoryQuestionsGetTheInventoryWhole() {
        for (String question : List.of("Where is each credential kept and how do I rotate a secret?",
                "What is the difference between rotation versus recovery for our secrets?")) {
            assertThat(builder.categoryIdFor(question)).as(question).isEqualTo("credential_inventory");
            KnowledgeNode hit = builder.buildContext(question, HouseholdRole.ADMINISTRATOR).stream()
                .filter(n -> n.entityRef().endsWith("#where-each-credential-lives-and-how-it-changes")).findFirst().orElse(null);
            assertThat(hit).as(question).isNotNull();
            assertThat(hit.content()).as(question).doesNotContain("section truncated");
        }
        assertThat(builder.categoryIdsFor("Where is the Vaultwarden master password?")).doesNotContain("credential_inventory");
    }

    /**
     * #490 review (HOLD 6976572246): a credential-location question that mentions Ansible Vault must still get the
     * credential guidance. The overlapping "vault edit" alias once let the inventory outrank credential_redaction and
     * drop credentials-and-access entirely.
     */
    @Test
    void ansibleVaultCredentialQuestionsKeepTheCredentialGuidance() {
        for (String question : List.of("Where is the password for an Ansible Vault edit?",
                "Where is the token for an Ansible Vault edit?",
                "Where is the secret for an Ansible Vault edit?")) {
            assertThat(builder.categoryIdFor(question)).as(question).isEqualTo("credential_redaction");
            List<KnowledgeNode> nodes = builder.buildContext(question, HouseholdRole.ADMINISTRATOR);
            KnowledgeNode access = nodes.stream().filter(n -> n.entityRef().endsWith("#credentials-and-access")).findFirst().orElse(null);
            assertThat(access).as(question + " delivers credentials-and-access").isNotNull();
            assertThat(access.content()).as(question).doesNotContain("section truncated");
            assertThat(nodes).as(question).noneMatch(n -> n.entityRef().endsWith("#where-each-credential-lives-and-how-it-changes"));
        }
    }

    @Test
    void credentialLocationAndEverydayQuestionsKeepTheirCategories() {
        Map<String, String> unchanged = Map.of(
            "Where is the Vaultwarden master password?", "credential_redaction",
            "Where is the Home Assistant password?", "credential_redaction",
            "Why am I not getting phone notifications?", "phone_notifications",
            "How do I add a managed user?", "managed_users",
            "How do I make a guest link?", "access_sharing");
        unchanged.forEach((q, category) -> assertThat(builder.categoryIdFor(q)).as(q).isEqualTo(category));
        assertThat(refs("Where is the Vaultwarden master password?", HouseholdRole.ADMINISTRATOR))
            .anyMatch(r -> r.endsWith("#credentials-and-access"));
        assertThat(builder.categoryIdsFor("What's the Blink login?"))
            .doesNotContain("identity_admin", "identity_sessions", "identity_guest_link_checks", "identity_sign_in_link");
    }

    @Test
    void theNewSectionsReachOnlyAnAdministrator() {
        for (String question : List.of("How do I make someone an administrator?",
                "Where is each credential kept and how do I rotate a secret?")) {
            assertThat(refs(question, HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).as(question)
                .noneMatch(r -> r.endsWith(ADMIN) || r.endsWith("#where-each-credential-lives-and-how-it-changes"));
            assertThat(refs(question, null)).as(question).isEmpty();
        }
    }

    private List<String> refs(String question, HouseholdRole role) {
        return builder.buildContext(question, role).stream().map(KnowledgeNode::entityRef).toList();
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
