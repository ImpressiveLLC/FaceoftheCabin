package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2 tail I04 (#498 plan). "What scopes can a guest link have?" used to select only general sharing guidance. The
 * guest-link-qualified phrase "guest link have" now selects the guest-link server-checks section, delivered whole to an
 * administrator. The section is administrator-only, as are the sharing sections, so other roles receive nothing, as before.
 * Scope questions about other products are not captured (#505 advisory, the #503 boundary), and credential questions keep
 * credentials-and-access whole.
 */
class AskGuestLinkScopesContextTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final String GUIDE = "ai-assistant/user-guide/access-and-storage.md";
    private static final String SECTION = "what-the-server-checks-when-a-guest-link-is-made";
    private static final String QUESTION = "What scopes can a guest link have?";

    private AskContextBuilder builder;
    private ReviewedDocumentSource source;

    @BeforeEach
    void setUp() {
        assertThat(REPO_DOCS.resolve("ai-assistant/user-guide")).isDirectory();
        source = new ReviewedDocumentSource(REPO_DOCS.toString());
        builder = new AskContextBuilder(new NoNodes(), source, new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    @Test
    void anAdministratorGetsTheScopeSectionWholeAndNoOtherRoleDoes() {
        assertThat(builder.categoryIdFor(QUESTION)).isEqualTo("identity_guest_link_scopes");
        String expected = source.section(GUIDE, SECTION).orElseThrow().text();
        List<KnowledgeNode> admin = builder.buildContext(QUESTION, HouseholdRole.ADMINISTRATOR);
        assertThat(admin).anyMatch(n -> n.entityRef().endsWith(GUIDE + "#" + SECTION) && n.content().equals(expected));
        assertThat(total(admin)).isLessThanOrEqualTo(6000);
        for (HouseholdRole role : Arrays.asList(HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD,
                HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null)) {
            assertThat(builder.buildContext(QUESTION, role)).as(String.valueOf(role)).noneMatch(n -> n.entityRef().endsWith("#" + SECTION));
        }
    }

    @Test
    void scopeQuestionsAboutOtherProductsAreNotCaptured() {
        for (String q : List.of("What scopes can a Google sign-in have?", "What scopes can a Home Assistant account have?",
                "What scopes can the monitoring key use?", "What scopes can Home Assistant tokens have?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).doesNotContain("identity_guest_link_scopes", "identity_guest_link_checks");
            for (HouseholdRole role : HouseholdRole.values()) {
                assertThat(builder.buildContext(q, role)).as(q + " " + role).noneMatch(n -> n.entityRef().endsWith("#" + SECTION));
            }
        }
        assertThat(builder.categoryIdFor("How do I make a guest link?")).isEqualTo("access_sharing");
        assertThat(builder.categoryIdsFor("How do I make a guest link?")).doesNotContain("identity_guest_link_scopes");
    }

    @Test
    void credentialQuestionsKeepCredentialGuidanceWhole() {
        String guidance = source.section("ai-assistant/user-guide/configuration.md", "credentials-and-access").orElseThrow().text();
        for (String q : List.of("Where is the guest link token?", "What scopes can a guest link token have, and where is the token?",
                "Where is the secret for a guest link?")) {
            assertThat(builder.categoryIdFor(q)).as(q).isEqualTo("credential_redaction");
            List<KnowledgeNode> nodes = builder.buildContext(q, HouseholdRole.ADMINISTRATOR);
            assertThat(nodes).as(q).anyMatch(n -> n.entityRef().endsWith("#credentials-and-access") && n.content().equals(guidance));
            assertThat(total(nodes)).as(q).isLessThanOrEqualTo(6000);
        }
    }

    private static int total(List<KnowledgeNode> nodes) {
        return nodes.stream().mapToInt(n -> n.content().length()).sum();
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
