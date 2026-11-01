package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One source of truth for who may be told what: the context map's audience for a guide section. The guide page states the
 * same thing in its own "Audience:" line, and a person reading the page (or a model quoting it) must not be told something
 * different. This test reads every mapped section and checks that its Audience line, where it has one, agrees with the map:
 * a section the map lets a household member see must say household; a section the map keeps for administrators must not say
 * household. (W-136 follow-up to the audience split.)
 */
class GuideAudienceLinesTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final Path MAP = REPO_DOCS.resolve("ai-assistant/rag/context-fixtures-r2.json");

    private ReviewedDocumentSource source;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.isRegularFile(MAP) && Files.isDirectory(REPO_DOCS.resolve("ai-assistant/user-guide")), "repo docs/ not reachable");
        source = new ReviewedDocumentSource(REPO_DOCS.toString());
    }

    /** The first "Audience:" line in the section text, lower-cased, or empty when the section has none. */
    private Optional<String> audienceLine(String path, String section) {
        return source.section(path, section).flatMap(s -> s.text().lines().map(String::strip)
            .filter(l -> l.toLowerCase().startsWith("audience:")).findFirst()).map(String::toLowerCase);
    }

    @Test
    void everyPageAudienceLineAgreesWithTheMap() throws IOException {
        JsonNode categories = new ObjectMapper().readTree(MAP.toFile()).get("categories");
        List<String> disagree = new ArrayList<>();
        int checked = 0;
        for (JsonNode category : categories) {
            for (JsonNode ref : category.path("contextDocuments")) {
                String path = ref.get("path").asText();
                String section = ref.get("section").asText();
                String mapAudience = ref.path("audience").asText("administrator");
                Optional<String> line = audienceLine(path, section);
                if (line.isEmpty()) continue;
                checked++;
                boolean pageSaysHousehold = line.get().startsWith("audience: household");
                if ("household".equals(mapAudience) && !pageSaysHousehold) {
                    disagree.add(category.get("id").asText() + " " + path + "#" + section + ": map household, page '" + abbreviate(line.get()) + "'");
                }
                if (!"household".equals(mapAudience) && pageSaysHousehold) {
                    disagree.add(category.get("id").asText() + " " + path + "#" + section + ": map administrator, page '" + abbreviate(line.get()) + "'");
                }
            }
        }
        assertThat(checked).as("sections with an Audience line").isGreaterThan(30);
        assertThat(disagree).as("page Audience lines that disagree with the context map").isEmpty();
    }

    @Test
    void theTwentyFourSectionsOfTheAudienceSplitAllStateTheirAudience() throws IOException {
        List<String> household = List.of("alert-levels-and-the-marks-on-the-tabs", "status-checks-what-needs-attention-right-now",
            "open-device-from-an-alert", "ignore-remind-me-in-and-restore", "phone-notifications-what-reaches-your-phone-today",
            "backend-rules-card-versus-home-assistant-and-node-red", "who-can-see-and-change-what", "what-a-workflow-is-and-where-it-runs",
            "can-a-workflow-run-on-a-schedule-or-at-a-time-of-day", "why-a-device-says-not-responding-late-or-has-a-blank-card",
            "find-a-device-filters-groups-and-layout", "what-candidates-available-assigned-saved-for-later-and-ignored-mean");
        List<String> admin = List.of("disk-space-warnings", "a-water-leak-alert-and-turning-the-water-back-on",
            "reset-mark-seen-and-why-there-is-no-undo", "turn-a-workflow-on-or-off-run-it-by-hand-and-see-when-it-ran",
            "change-how-a-device-shows-on-monitoring", "on-the-way-warm-up-the-cabin-before-you-arrive", "pair-a-zigbee-device",
            "remove-a-device-and-bring-it-back", "rename-a-device-or-set-its-room-and-area", "scan-for-wifi-devices-at-home",
            "the-enabled-switch", "turn-a-device-on-or-off-from-the-app");
        JsonNode categories = new ObjectMapper().readTree(MAP.toFile()).get("categories");
        List<String> missing = new ArrayList<>();
        for (JsonNode category : categories) {
            for (JsonNode ref : category.path("contextDocuments")) {
                String section = ref.get("section").asText();
                boolean inSplit = household.contains(section) || admin.contains(section);
                if (inSplit && audienceLine(ref.get("path").asText(), section).isEmpty()) missing.add(section);
            }
        }
        assertThat(missing).as("split sections with no Audience line").isEmpty();
    }

    private static String abbreviate(String line) {
        return line.length() > 60 ? line.substring(0, 60) : line;
    }

    private static final class NoNodes implements KnowledgeNodeRepository {
        @Override public void upsert(KnowledgeNode node) { }
        @Override public List<KnowledgeNode> findByEntityRef(String entityRef) { return List.of(); }
        @Override public List<KnowledgeNode> loadAll() { return List.of(); }
    }
}
