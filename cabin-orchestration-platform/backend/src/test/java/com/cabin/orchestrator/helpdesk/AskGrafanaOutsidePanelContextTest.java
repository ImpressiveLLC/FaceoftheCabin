package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2 tail M03 (#498 plan, Cowork #244 6981320153). The reported question "Grafana shows a blank page outside the embedded
 * panel; how do I sign in?" used to select generic user authentication on "sign in". It now delivers the existing household
 * Grafana login recovery section whole, to every role. Generic application sign-in questions keep their category, and
 * Grafana password, token and secret questions keep credentials-and-access whole.
 */
class AskGrafanaOutsidePanelContextTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final String GUIDE = "ai-assistant/user-guide/operations-recovery-reference.md";
    private static final String SECTION = "grafana-login-outside-the-embedded-panel";
    private static final String QUESTION = "Grafana shows a blank page outside the embedded panel; how do I sign in?";

    private AskContextBuilder builder;
    private ReviewedDocumentSource source;

    @BeforeEach
    void setUp() {
        assertThat(REPO_DOCS.resolve("ai-assistant/user-guide")).isDirectory();
        source = new ReviewedDocumentSource(REPO_DOCS.toString());
        builder = new AskContextBuilder(new NoNodes(), source, new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    @Test
    void theReportedQuestionDeliversTheGrafanaSectionWholeToEveryRole() {
        assertThat(builder.categoryIdFor(QUESTION)).isEqualTo("recovery_grafana_outside_panel");
        String expected = source.section(GUIDE, SECTION).orElseThrow().text();
        for (HouseholdRole role : Arrays.asList(HouseholdRole.values())) {
            assertDelivered(builder.buildContext(QUESTION, role), expected, String.valueOf(role));
        }
        assertDelivered(builder.buildContext(QUESTION, null), expected, "no role");
        assertThat(builder.categoryIdFor("Why is Grafana blank outside the embedded panel?")).as("short wording")
            .isEqualTo("recovery_grafana_outside_panel");
    }

    @Test
    void genericSignInQuestionsKeepTheirCategory() {
        for (String q : List.of("How do I sign in?", "I can't sign in to the app", "Who is signed in on the kiosk?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).doesNotContain("recovery_grafana_outside_panel");
        }
        assertThat(builder.categoryIdFor("How do I sign in?")).isEqualTo("user_auth_role");
    }

    @Test
    void grafanaCredentialQuestionsKeepCredentialGuidanceWhole() {
        String guidance = source.section("ai-assistant/user-guide/configuration.md", "credentials-and-access").orElseThrow().text();
        for (String q : List.of("Where do I find the Grafana login password?",
                "Grafana shows a blank page; where is the admin password?",
                "Grafana shows a blank page outside the embedded panel; where is the token?",
                "Grafana shows a blank page; what is the secret for it?")) {
            assertThat(builder.categoryIdFor(q)).as(q).isEqualTo("credential_redaction");
            List<KnowledgeNode> nodes = builder.buildContext(q, HouseholdRole.ADMINISTRATOR);
            assertThat(nodes).as(q).anyMatch(n -> n.entityRef().endsWith("#credentials-and-access") && n.content().equals(guidance));
            assertThat(nodes.stream().mapToInt(n -> n.content().length()).sum()).as(q).isLessThanOrEqualTo(6000);
        }
    }

    private static void assertDelivered(List<KnowledgeNode> nodes, String expected, String who) {
        assertThat(nodes).as(who).anyMatch(n -> n.entityRef().endsWith(GUIDE + "#" + SECTION) && n.content().equals(expected));
        assertThat(nodes.stream().mapToInt(n -> n.content().length()).sum()).as(who).isLessThanOrEqualTo(6000);
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
