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

    @Test
    void identityQuestionsReachTheIdentitySections() {
        Map<String, String> pinned = Map.of(
            "How do I make someone an administrator?", "#how-someone-becomes-an-administrator",
            "What does ADMIN_EMAILS do?", "#how-someone-becomes-an-administrator",
            "How long does the 30-day session last and what ends it?", "#sign-in-sessions-google-30-day-sessions-and-managed-sessions",
            "Why does the managed sign-in link work without signing in?", "#why-the-sign-in-link-opens-without-signing-in",
            "What guest link scope does the server require?", "#what-the-server-checks-when-a-guest-link-is-made");
        pinned.forEach((question, section) -> {
            assertThat(builder.categoryIdFor(question)).as(question).isEqualTo("identity_sessions");
            assertThat(refs(question, HouseholdRole.ADMINISTRATOR)).as(question).anyMatch(r -> r.endsWith(section));
        });
    }

    @Test
    void inventoryQuestionsGetTheInventoryAfterCredentialsAndAccess() {
        String question = "Where is each credential kept and how do I rotate a secret?";
        assertThat(builder.categoryIdFor(question)).isEqualTo("credential_redaction");
        List<String> refs = refs(question, HouseholdRole.ADMINISTRATOR);
        int access = indexOf(refs, "#credentials-and-access");
        int inventory = indexOf(refs, "#where-each-credential-lives-and-how-it-changes");
        assertThat(access).as("credentials-and-access present").isGreaterThanOrEqualTo(0);
        assertThat(inventory).as("inventory present and after credentials-and-access").isGreaterThan(access);
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
        assertThat(builder.categoryIdsFor("What's the Blink login?")).doesNotContain("identity_sessions");
    }

    @Test
    void theNewSectionsReachOnlyAnAdministrator() {
        for (String question : List.of("How do I make someone an administrator?",
                "Where is each credential kept and how do I rotate a secret?")) {
            assertThat(refs(question, HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).as(question)
                .noneMatch(r -> r.endsWith("#how-someone-becomes-an-administrator") || r.endsWith("#where-each-credential-lives-and-how-it-changes"));
            assertThat(refs(question, null)).as(question).isEmpty();
        }
    }

    private static int indexOf(List<String> refs, String suffix) {
        for (int i = 0; i < refs.size(); i++) if (refs.get(i).endsWith(suffix)) return i;
        return -1;
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
