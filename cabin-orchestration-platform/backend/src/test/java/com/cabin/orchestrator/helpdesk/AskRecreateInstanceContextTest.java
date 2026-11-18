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
 * C2 tail installation phrasings, part 1 (#498 plan, Cowork's #493 review questions). "How do I recreate the instance on a
 * new machine?" used to match nothing, and "What are the acceptance checks for recreating the instance?" went to device
 * onboarding. Each now delivers the existing administrator plan or acceptance section whole. The two clean-machine and
 * blank-machine phrasings are deliberately unchanged here (their sections do not fit beside the installation limits within
 * the budget) and stay known gaps pending a budget ruling. Other roles receive nothing, credential questions keep
 * credentials-and-access whole, and ordinary device-import acceptance questions keep their category.
 */
class AskRecreateInstanceContextTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final String GUIDE = "ai-assistant/user-guide/independent-installation.md";

    private AskContextBuilder builder;
    private ReviewedDocumentSource source;

    @BeforeEach
    void setUp() {
        assertThat(REPO_DOCS.resolve("ai-assistant/user-guide")).isDirectory();
        source = new ReviewedDocumentSource(REPO_DOCS.toString());
        builder = new AskContextBuilder(new NoNodes(), source, new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    @Test
    void theReportedQuestionsDeliverTheirSectionWholeToAnAdministratorOnly() {
        assertWhole("How do I recreate the instance on a new machine?", "recreation_instance_plan", "establish-the-actual-installation-plan");
        assertWhole("What are the acceptance checks for recreating the instance?", "recreation_instance_acceptance", "acceptance-record");
    }

    @Test
    void ordinaryAcceptanceAndInstallQuestionsKeepTheirCategory() {
        for (String q : List.of("What does acceptance mean for an imported device?", "How do I install on a clean machine?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).doesNotContain("recreation_instance_plan", "recreation_instance_acceptance");
        }
        assertThat(builder.categoryIdFor("How do I install on a clean machine?")).isEqualTo("installation");
    }

    @Test
    void credentialQuestionsKeepCredentialGuidanceWhole() {
        String guidance = source.section("ai-assistant/user-guide/configuration.md", "credentials-and-access").orElseThrow().text();
        for (String q : List.of("When I recreate the instance, where is the database password?",
                "Where is the token for the acceptance checks for recreating the instance?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).contains("credential_redaction");
            List<KnowledgeNode> nodes = builder.buildContext(q, HouseholdRole.ADMINISTRATOR);
            assertThat(nodes).as(q).anyMatch(n -> n.entityRef().endsWith("#credentials-and-access") && n.content().equals(guidance));
            assertThat(total(nodes)).as(q).isLessThanOrEqualTo(6000);
        }
    }

    private void assertWhole(String question, String category, String section) {
        assertThat(builder.categoryIdFor(question)).as(question).isEqualTo(category);
        String expected = source.section(GUIDE, section).orElseThrow().text();
        List<KnowledgeNode> admin = builder.buildContext(question, HouseholdRole.ADMINISTRATOR);
        assertThat(admin).as(question).anyMatch(n -> n.entityRef().endsWith(GUIDE + "#" + section) && n.content().equals(expected));
        assertThat(total(admin)).as(question).isLessThanOrEqualTo(6000);
        for (HouseholdRole role : Arrays.asList(HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD,
                HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null)) {
            assertThat(builder.buildContext(question, role)).as(question + " " + role).noneMatch(n -> n.entityRef().endsWith("#" + section));
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
