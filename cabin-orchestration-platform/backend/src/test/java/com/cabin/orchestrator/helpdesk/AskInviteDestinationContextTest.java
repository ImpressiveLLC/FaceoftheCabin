package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AskInviteDestinationContextTest {
    private static final String GUIDE = "ai-assistant/user-guide/configuration.md";
    private static final String SECTION = "reference-instance-ports-and-image-settings";
    private static final String QUESTION = "Where do invite links point, and which setting controls it?";
    private final ReviewedDocumentSource source = new ReviewedDocumentSource(Path.of("../../docs").toAbsolutePath().toString());

    private AskContextBuilder builder() {
        KnowledgeNodeRepository repo = mock(KnowledgeNodeRepository.class);
        when(repo.loadAll()).thenReturn(List.of());
        return new AskContextBuilder(repo, source, new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    private void containsWhole(List<KnowledgeNode> nodes, String section) {
        String full = source.section(GUIDE, section).orElseThrow().text();
        assertThat(nodes).anySatisfy(n -> {
            assertThat(n.entityRef()).isEqualTo("doc:" + GUIDE + "#" + section);
            assertThat(n.content()).isEqualTo(full);
        });
        assertThat(nodes.stream().mapToInt(n -> n.content().length()).sum()).isLessThanOrEqualTo(6000);
    }

    @Test
    void reportedQuestionDeliversTheCompleteDestinationReference() {
        containsWhole(builder().buildContext(QUESTION, HouseholdRole.ADMINISTRATOR), SECTION);
    }

    @Test
    void destinationReferenceRemainsAdministratorOnly() {
        for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.ADULT_HOUSEHOLD_MEMBER, HouseholdRole.CHILD,
                HouseholdRole.KIOSK_DISPLAY, HouseholdRole.SERVICE, null}) {
            assertThat(builder().buildContext(QUESTION, role))
                .noneMatch(n -> n.entityRef().equals("doc:" + GUIDE + "#" + SECTION));
        }
    }

    @Test
    void credentialVariantsKeepBothCompleteSectionsWithinBudget() {
        for (String credential : List.of("password", "token", "secret")) {
            for (String q : List.of("Where do invite links point, and what " + credential + " is needed?",
                    "What " + credential + " is used where invite links point?",
                    "Where do invite links point, where do invite links point, and what " + credential + " is needed?")) {
                List<KnowledgeNode> nodes = builder().buildContext(q, HouseholdRole.ADMINISTRATOR);
                containsWhole(nodes, "credentials-and-access");
                containsWhole(nodes, SECTION);
            }
        }
    }

    @Test
    void ordinaryManagedUserQuestionKeepsItsExistingCategory() {
        assertThat(builder().categoryIdsFor("How do I add someone who doesn't have a Google account?"))
            .containsExactly("managed_users");
    }
}
