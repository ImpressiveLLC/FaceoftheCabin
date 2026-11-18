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
 * C2 tail M04 (residual-gap ordering #513, Cowork ruling on #244). "What does the external dead-man monitor do if the hub goes
 * silent?" used to tie self_healing_recovery and recovery_deadman on "dead-man"; the 4648-character self-healing section
 * left no room for the dead-man section. The external-qualified phrase now selects the dead-man section alone and delivers it
 * whole to an administrator. Blink's own dead-man alert and generic dead-man wording keep the self-healing section, and
 * credential wording keeps credentials-and-access whole.
 */
class AskExternalDeadmanContextTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final String GUIDE = "ai-assistant/user-guide/operations-recovery-reference.md";
    private static final String SECTION = "external-dead-man-monitoring";
    private static final String QUESTION = "What does the external dead-man monitor do if the hub goes silent?";

    private AskContextBuilder builder;
    private ReviewedDocumentSource source;

    @BeforeEach
    void setUp() {
        assertThat(REPO_DOCS.resolve("ai-assistant/user-guide")).isDirectory();
        source = new ReviewedDocumentSource(REPO_DOCS.toString());
        builder = new AskContextBuilder(new NoNodes(), source, new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    @Test
    void theExternalMonitorQuestionGetsTheDeadmanSectionWholeForAnAdministratorOnly() {
        assertThat(builder.categoryIdFor(QUESTION)).isEqualTo("recovery_external_deadman");
        String expected = source.section(GUIDE, SECTION).orElseThrow().text();
        List<KnowledgeNode> admin = builder.buildContext(QUESTION, HouseholdRole.ADMINISTRATOR);
        assertThat(admin).anyMatch(n -> n.entityRef().endsWith(GUIDE + "#" + SECTION) && n.content().equals(expected));
        assertThat(total(admin)).isLessThanOrEqualTo(6000);
        for (HouseholdRole role : Arrays.asList(HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD,
                HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null)) {
            assertThat(builder.buildContext(QUESTION, role)).as(String.valueOf(role)).noneMatch(n -> n.entityRef().endsWith("#" + SECTION));
        }
    }

    /** #513 review (Codex HOLD 6988469412): Blink's motion dead-man alert must keep its own guidance. */
    @Test
    void blinkAndGenericDeadmanWordingIsNotCaptured() {
        for (String q : List.of("What does the Blink dead-man monitor do?", "What is the Blink dead-man alert?",
                "What does the dead-man alert mean?", "Is there a dead-man check?")) {
            assertThat(builder.categoryIdsFor(q)).as(q).doesNotContain("recovery_external_deadman");
        }
        assertThat(builder.categoryIdFor("What does the Blink dead-man monitor do?")).isEqualTo("self_healing_recovery");
    }

    @Test
    void credentialWordingKeepsCredentialGuidanceWhole() {
        String guidance = source.section("ai-assistant/user-guide/configuration.md", "credentials-and-access").orElseThrow().text();
        for (String q : List.of("Where is the external dead-man token?", "Where is the password for the external dead-man monitor?",
                "What is the secret for the external dead-man check?")) {
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
