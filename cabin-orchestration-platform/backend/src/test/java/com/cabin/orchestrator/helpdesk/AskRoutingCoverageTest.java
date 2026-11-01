package com.cabin.orchestrator.helpdesk;

import com.cabin.orchestrator.devices.KnowledgeNodeRepository;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.security.HouseholdRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * W-117 routing, item A: phrasings from the coverage map (docs/ai-assistant/corpus/ask-coverage-tools/phrasings.json) that a
 * person would ask about alerts, workflows, access, storage, maintenance and devices reach the category whose guide section
 * answers them, instead of no category (which falls back to word overlap over knowledge nodes). The audience of each section
 * stays as proposed: a non-administrator never receives an administrator section.
 */
class AskRoutingCoverageTest {

    private static final Path REPO_DOCS = Path.of("../../docs").toAbsolutePath().normalize();
    private static final Path FIXTURE_COPY = REPO_DOCS.resolve("ai-assistant/rag/context-fixtures-r2.json");
    private static final Path BACKEND_COPY = Path.of("src/main/resources/rag/context-fixtures-r2.json").toAbsolutePath();

    private AskContextBuilder builder;

    @BeforeEach
    void setUp() {
        assumeTrue(Files.isRegularFile(FIXTURE_COPY) && Files.isDirectory(REPO_DOCS.resolve("ai-assistant/user-guide")),
            "repo docs/ not reachable from the test working directory");
        builder = new AskContextBuilder(new NoNodes(), new ReviewedDocumentSource(REPO_DOCS.toString()),
            new DefaultResourceLoader(), "classpath:rag/context-fixtures-r2.json", 6000);
    }

    @Test
    void theTwoCopiesOfTheMapAreByteIdentical() throws IOException {
        assumeTrue(Files.isRegularFile(BACKEND_COPY));
        String docs = Files.readString(FIXTURE_COPY).replace("\r\n", "\n");
        String backend = Files.readString(BACKEND_COPY).replace("\r\n", "\n");
        assertThat(backend).isEqualTo(docs);
    }

    @Test
    void pinnedPhrasingsPerTopicReachTheirCategory() {
        Map<String, String> pinned = new LinkedHashMap<>();
        // alerts
        pinned.put("Where do I see what's wrong at the cabin right now?", "status_checks");
        pinned.put("How do I ignore an alert?", "alert_silencing");
        pinned.put("Will I get a notification on my phone if there's a leak?", "phone_notifications");
        pinned.put("There's a water leak alert, what do I do?", "leak_response");
        pinned.put("How do I undo what a workflow did?", "workflow_reset_undo");
        pinned.put("What are the built-in rules?", "rules_workflows");
        pinned.put("What's the difference between primary and maintenance?", "presence_roles_and_gaps");
        // workflows and access
        pinned.put("How do I create a new workflow?", "workflow_create");
        pinned.put("I made a workflow but it doesn't do anything", "workflow_run_history");
        pinned.put("What does Stays fired until someone clears it mean?", "workflow_settings");
        pinned.put("Can I schedule something to happen every night at 10?", "workflow_schedule");
        pinned.put("Who is allowed to change workflows?", "who_can_change");
        pinned.put("How do I give the insurance adjuster read-only access?", "access_sharing");
        pinned.put("How do I add someone who doesn't have a Google account?", "managed_users");
        // storage and maintenance
        pinned.put("The disk is almost full, what do I do?", "storage_disk");
        pinned.put("How do I free up disk space?", "storage_disk");
        pinned.put("Will it restart on its own if something crashes?", "self_healing_recovery");
        pinned.put("How do I check everything is working?", "health_verification");
        pinned.put("Why aren't Home Assistant devices updating?", "configuration_binding");
        pinned.put("How do updates get deployed?", "deployment_source");
        pinned.put("How do I restore from a backup?", "backup_restore_recipes");
        // devices
        pinned.put("How do I add a new Zigbee sensor?", "device_add");
        pinned.put("A new device showed up, what do I do with it?", "device_management");
        pinned.put("How do I rename a device?", "device_settings");
        pinned.put("How do I turn the heater on from the app?", "device_control");
        pinned.put("Why does my sensor say offline?", "device_health");
        pinned.put("Why is my plug listed twice?", "zigbee_duplicate");
        pinned.put("Why is the camera not showing video?", "camera_frames");
        pinned.forEach((question, category) -> assertThat(builder.categoryIdFor(question)).as(question).isEqualTo(category));
    }

    /**
     * Every in-scope phrasing of the three routing proposals (120 of the 150) and the category the map sends it to
     * (src/test/resources/rag/routing-pins.json, written by docs/ai-assistant/corpus/ask-coverage-tools/write_pins.py,
     * which refuses a pin the proposals do not accept). Changing a keyword so that any of them moves fails here, naming it.
     */
    @Test
    void everyInScopePhrasingStillReachesItsPinnedCategory() throws IOException {
        com.fasterxml.jackson.databind.JsonNode pins = new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(getClass().getResourceAsStream("/rag/routing-pins.json"));
        assertThat(pins.size()).as("pinned phrasings").isEqualTo(120);
        List<String> moved = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode pin : pins) {
            String routed = builder.categoryIdFor(pin.get("question").asText());
            if (!pin.get("category").asText().equals(routed)) {
                moved.add(pin.get("n").asInt() + " " + pin.get("question").asText() + " -> " + routed + " (pinned " + pin.get("category").asText() + ")");
            }
        }
        assertThat(moved).as("phrasings that no longer reach their pinned category").isEmpty();
    }

    @Test
    void theWordWorkflowAloneStillReachesTheRulesCategory() {
        assertThat(builder.categoryIdFor("Which triggers can a workflow use, and can it lock a door?")).isEqualTo("rules_workflows");
    }

    @Test
    void aNonAdministratorNeverReceivesAnAdministratorOnlySection() {
        for (String question : List.of(
                "What does Stays fired until someone clears it mean?",
                "How do I add someone who doesn't have a Google account?",
                "How do I give the insurance adjuster read-only access?")) {
            for (HouseholdRole role : new HouseholdRole[] {HouseholdRole.CHILD, HouseholdRole.ADULT_HOUSEHOLD_MEMBER, null}) {
                List<String> refs = builder.buildContext(question, role).stream().map(KnowledgeNode::entityRef).toList();
                assertThat(refs).as(question + " as " + role).noneMatch(r -> r.endsWith("#workflow-settings-after-it-fires-cooldown-and-broken-targets")
                    || r.endsWith("#add-someone-without-a-google-account-managed-users")
                    || r.endsWith("#share-read-only-access-with-a-link-guest-access"));
            }
        }
    }

    @Test
    void anAdministratorGetsTheTrimSectionAndAHouseholdMemberGetsNothingForDiskSpace() {
        String question = "How do I free up disk space?";
        List<String> admin = builder.buildContext(question, HouseholdRole.ADMINISTRATOR).stream().map(KnowledgeNode::entityRef).toList();
        assertThat(admin).anyMatch(r -> r.endsWith("#disk-space-warnings"));
        assertThat(admin).anyMatch(r -> r.endsWith("#trim-old-backups"));
        // Disk space is about changing the system: administrator-only (Nate, option 3). A household question there gets no
        // reviewed section and falls through to the word-overlap path, exactly as it did before routing.
        assertThat(builder.categoryIdFor(question)).isEqualTo("storage_disk");
        assertThat(builder.buildContext(question, HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).isEmpty();
        assertThat(builder.buildContext(question, null)).isEmpty();
    }

    /** The audience split, pinned: household keeps explain-and-status answers; anything that changes rules, workflows, devices or disk is administrator-only. */
    private static final List<String> HOUSEHOLD = List.of(
        "alert-levels-and-the-marks-on-the-tabs", "status-checks-what-needs-attention-right-now", "open-device-from-an-alert",
        "ignore-remind-me-in-and-restore", "phone-notifications-what-reaches-your-phone-today",
        "backend-rules-card-versus-home-assistant-and-node-red", "who-can-see-and-change-what",
        "what-a-workflow-is-and-where-it-runs", "can-a-workflow-run-on-a-schedule-or-at-a-time-of-day",
        "why-a-device-says-not-responding-late-or-has-a-blank-card", "find-a-device-filters-groups-and-layout",
        "what-candidates-available-assigned-saved-for-later-and-ignored-mean");
    private static final List<String> ADMINISTRATOR_ONLY = List.of(
        "disk-space-warnings", "a-water-leak-alert-and-turning-the-water-back-on", "reset-mark-seen-and-why-there-is-no-undo",
        "turn-a-workflow-on-or-off-run-it-by-hand-and-see-when-it-ran", "change-how-a-device-shows-on-monitoring",
        "on-the-way-warm-up-the-cabin-before-you-arrive", "pair-a-zigbee-device", "remove-a-device-and-bring-it-back",
        "rename-a-device-or-set-its-room-and-area", "scan-for-wifi-devices-at-home", "the-enabled-switch",
        "turn-a-device-on-or-off-from-the-app");

    @Test
    void theAudienceSplitIsExactlyAsDecided() throws IOException {
        com.fasterxml.jackson.databind.JsonNode categories = new com.fasterxml.jackson.databind.ObjectMapper().readTree(FIXTURE_COPY.toFile()).get("categories");
        Map<String, java.util.Set<String>> audiences = new LinkedHashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode category : categories) {
            for (com.fasterxml.jackson.databind.JsonNode ref : category.path("contextDocuments")) {
                audiences.computeIfAbsent(ref.get("section").asText(), k -> new java.util.HashSet<>()).add(ref.get("audience").asText());
            }
        }
        for (String section : HOUSEHOLD) {
            assertThat(audiences.get(section)).as(section).containsExactly("household");
        }
        for (String section : ADMINISTRATOR_ONLY) {
            assertThat(audiences.get(section)).as(section).containsExactly("administrator");
        }
    }

    @Test
    void householdKeepsTheExplainAndStatusAnswers() {
        assertThat(refs("Where do I see what's wrong at the cabin right now?", HouseholdRole.ADULT_HOUSEHOLD_MEMBER))
            .anyMatch(r -> r.endsWith("#status-checks-what-needs-attention-right-now"));
        assertThat(refs("How do I ignore an alert?", HouseholdRole.ADULT_HOUSEHOLD_MEMBER))
            .anyMatch(r -> r.endsWith("#ignore-remind-me-in-and-restore"));
        assertThat(refs("Why does my sensor say offline?", HouseholdRole.CHILD))
            .anyMatch(r -> r.endsWith("#why-a-device-says-not-responding-late-or-has-a-blank-card"));
        assertThat(refs("Can I schedule something to happen every night at 10?", HouseholdRole.ADULT_HOUSEHOLD_MEMBER))
            .anyMatch(r -> r.endsWith("#can-a-workflow-run-on-a-schedule-or-at-a-time-of-day"));
    }

    @Test
    void aQuestionAboutChangingThingsGetsNoReviewedSectionForAHouseholdMember() {
        // The category all of whose sections are now administrator-only: no reviewed section, so the word-overlap path as before.
        for (String question : List.of(
                "How do I turn the heater on from the app?",
                "How do I rename a device?",
                "How do I add a new Zigbee sensor?",
                "How do I free up disk space?",
                "How do I undo what a workflow did?")) {
            assertThat(refs(question, HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).as(question).isEmpty();
            assertThat(refs(question, HouseholdRole.ADMINISTRATOR)).as(question + " (administrator)").isNotEmpty();
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

    @Test
    void aHouseholdMemberAskingWhatARoleCanDoGetsTheByAreaTable() {
        for (String q : List.of("Who is allowed to change workflows?", "What can a viewer do?")) {
            assertThat(refs(q, HouseholdRole.ADULT_HOUSEHOLD_MEMBER)).as(q).anyMatch(r -> r.endsWith("#what-each-role-can-do-by-area"));
        }
    }
}
