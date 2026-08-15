package com.cabin.orchestrator.mqtt;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.kafka.EventPublisher;
import com.cabin.orchestrator.presence.PresenceProfile;
import com.cabin.orchestrator.presence.PresenceService;
import com.cabin.orchestrator.presence.PresenceSignalRegistry;
import com.cabin.orchestrator.security.SecurityStateRegistry;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Regression coverage for the 2026-08-07 finding: handleCameraTopic()
 * never called DeviceRegistry.update() for any camera MQTT message, so a
 * camera's lastSeen was set once (however it first got registered) and
 * never refreshed -- DeviceHealthMonitor's 5-minute camera stale
 * threshold then always fired exactly 5 minutes after that one
 * registration and the camera could never recover on its own.
 *
 * Also covers the 2026-08-08 finding: the active PresenceProfile was
 * only ever set manually from the toolbar, with no real signal behind
 * it despite AutomationRuleService using it for real security-severity
 * decisions -- see handlePresenceTopic's tests below.
 *
 * DeviceRegistry/PresenceSignalRegistry are plain in-memory maps,
 * EventPublisher safely no-ops when constructed without @PostConstruct
 * init() (producer stays null, publish() logs and returns), and
 * PresenceService's JdbcTemplate is mocked (Mockito, via
 * spring-boot-starter-test) rather than pointed at real Postgres --
 * these tests exercise the real derivation logic in PresenceService/
 * PresenceSignalRegistry, just without needing a live DB connection for
 * what's fundamentally in-memory "who's here right now" state (see
 * PresenceSignalRegistry's own comment on why it's not Postgres-backed).
 * This exercises MqttBridgeService.messageArrived() -- the real public
 * entry point Paho calls -- directly against real Kafka/DB.
 *
 * 2026-08-15: Testcontainers Postgres was added specifically for
 * FrigateEventReconciliationService -- handleFrigateDetectionEvent() now
 * calls its upsertDetection() directly instead of publishing a CabinEvent
 * through Kafka (see that service's own javadoc for why: MQTT/REST share
 * one idempotent upsert path, and going through Kafka's
 * ON-CONFLICT-DO-NOTHING save() would defeat the "hasClip can flip from
 * false to true later" requirement this whole change exists for). A mock
 * eventPublisher stays for the presence/security/device-telemetry tests
 * below, which still go through the ordinary publish() path unchanged.
 */
@Testcontainers
class MqttBridgeServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private DeviceRegistry registry;
    private PresenceService presenceService;
    private PresenceSignalRegistry presenceSignalRegistry;
    private SecurityStateRegistry securityStateRegistry;
    private EventPublisher eventPublisher;
    private CabinEventService eventService;
    private MqttBridgeService bridge;

    @BeforeEach
    void setUp() {
        registry = new DeviceRegistry(List.of());
        presenceSignalRegistry = new PresenceSignalRegistry();
        presenceService = new PresenceService(mock(JdbcTemplate.class), presenceSignalRegistry);
        securityStateRegistry = new SecurityStateRegistry();
        eventPublisher = mock(EventPublisher.class);
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        eventService = new CabinEventService(jdbc);
        // frigateUrl/backfillDays/overlapSeconds/pageLimit are irrelevant
        // here -- these tests only exercise upsertDetection() via the MQTT
        // path, never initialBackfill()/periodicReconcile()'s own HTTP
        // call, so a bogus URL is fine. See FrigateEventReconciliationServiceTest
        // for coverage of the REST fetch/cursor/health side.
        FrigateEventReconciliationService frigateReconciliation =
            new FrigateEventReconciliationService(eventService, registry, "http://unused:0", 5, 300, 200);
        bridge = new MqttBridgeService(registry, eventPublisher, presenceService, presenceSignalRegistry,
            securityStateRegistry, frigateReconciliation);
    }

    private void deliver(String topic, String payload) throws Exception {
        bridge.messageArrived(topic, new MqttMessage(payload.getBytes()));
    }

    @Test
    void motionTopicAutoRegistersAndMarksCameraOnline() throws Exception {
        assertNull(registry.get("driveway"));

        deliver("cabin/camera/driveway/motion", "ON");

        DeviceStatus status = registry.get("driveway");
        assertNotNull(status, "camera should be auto-registered on first motion message");
        assertEquals(DeviceType.CAMERA, status.type());
        assertEquals("ONLINE", status.state());
        assertEquals("cabin", status.location());
    }

    // 2026-08-15: AldrichFront (a Home-location Blink camera relayed through
    // the cabin M920q's own blinkbridge/Frigate, on the same Blink account/
    // sync module as the cabin's driveway camera) still arrives on this same
    // cabin/camera/# subscription -- there is no separate home-location MQTT
    // broker or backend instance for it to arrive on instead. Before this,
    // every camera got hardcoded location "cabin" regardless of its own
    // name, so a home_-prefixed camera showed up mislabeled -- confirmed
    // against the real code, not assumed, while wiring AldrichFront in.
    @Test
    void homePrefixedCameraIsRegisteredWithHomeLocation() throws Exception {
        assertNull(registry.get("home_aldrich_front"));

        deliver("cabin/camera/home_aldrich_front/motion", "ON");

        DeviceStatus status = registry.get("home_aldrich_front");
        assertNotNull(status, "camera should be auto-registered on first motion message");
        assertEquals("home", status.location(),
            "a home_-prefixed camera id must be tagged location=home even though it arrived " +
            "on the cabin subscription, so GET /api/events?location=home can find it");
    }

    @Test
    void perLabelCountTopicAlsoTouchesTheCamera() throws Exception {
        deliver("cabin/camera/driveway/car", "1");

        DeviceStatus status = registry.get("driveway");
        assertNotNull(status, "camera should be auto-registered from a per-label count topic too");
        assertEquals("ONLINE", status.state());
    }

    @Test
    void repeatedMotionRefreshesLastSeenInsteadOfOnlyRegisteringOnce() throws Exception {
        deliver("cabin/camera/driveway/motion", "OFF");
        Instant firstSeen = registry.get("driveway").lastSeen();

        Thread.sleep(5);
        deliver("cabin/camera/driveway/motion", "ON");
        Instant secondSeen = registry.get("driveway").lastSeen();

        assertTrue(secondSeen.isAfter(firstSeen),
            "a later camera message must push lastSeen forward, or DeviceHealthMonitor's " +
            "5-minute stale threshold will fire and never recover, exactly like the 2026-08-07 incident");
    }

    @Test
    void touchingACameraPreservesItsExistingAttributes() throws Exception {
        deliver("cabin/camera/driveway/motion", "ON");
        // simulate an attribute a future enhancement might attach (e.g. resolution)
        DeviceStatus withAttrs = registry.get("driveway");
        registry.update(new DeviceStatus(withAttrs.deviceId(), withAttrs.type(), withAttrs.name(),
            withAttrs.state(), withAttrs.lastSeen(), java.util.Map.of("resolution", "1080p"), withAttrs.location()));

        deliver("cabin/camera/driveway/car", "2");

        assertEquals("1080p", registry.get("driveway").attributes().get("resolution"),
            "touchCamera() must not clobber attributes set by other paths");
    }

    @Test
    void availableTopicIsIgnoredSinceItDoesNotNameACamera() throws Exception {
        // cabin/camera/available is Frigate's single bridge-wide topic --
        // parts.length == 2, so handleCameraTopic's per-camera branches
        // must not misinterpret it as a camera named "available".
        deliver("cabin/camera/available", "online");

        assertNull(registry.get("available"),
            "the bridge-wide availability topic must never be registered as a camera device");
    }

    // 2026-08-15: handleFrigateDetectionEvent() now writes through
    // FrigateEventReconciliationService.upsertDetection() -> CabinEventService
    // .upsert() directly (Postgres), not eventPublisher.publish() -- see this
    // class's own javadoc. Assertions read the persisted row back instead of
    // capturing a publish() call that no longer happens for this path.
    @Test
    void frigateDetectionUsesClassifierInsteadOfHardcodedInfo() throws Exception {
        deliver("cabin/camera/events", """
            {"type":"new","after":{"id":"1723000000.123456-abcdef","camera":"driveway","label":"person","score":0.93,"alarm":true}}
            """);

        List<CabinEvent> events = eventService.recent("driveway", 10, Instant.now().minusSeconds(60));
        assertEquals(1, events.size());
        CabinEvent event = events.get(0);
        assertEquals("CRITICAL", event.severity());
        assertEquals("DETECTION_NEW", event.eventType());
        assertEquals("driveway", event.sourceDeviceId());
        assertEquals("frigate:1723000000.123456-abcdef", event.eventId(),
            "must use the deterministic frigate:{id} key, not a fresh random UUID, or REST reconciliation can never dedupe against this same detection");
    }

    @Test
    void ordinaryFrigateDetectionRemainsInfo() throws Exception {
        deliver("cabin/camera/events", """
            {"type":"update","after":{"id":"1723000001.000000-fedcba","camera":"driveway","label":"person","score":0.71}}
            """);

        List<CabinEvent> events = eventService.recent("driveway", 10, Instant.now().minusSeconds(60));
        assertEquals(1, events.size());
        assertEquals("INFO", events.get(0).severity());
    }

    // 2026-08-15: without a Frigate event id there's nothing to key a
    // deterministic upsert on and nothing to ever fetch a clip for (see
    // handleFrigateDetectionEvent's own comment) -- must be skipped, not
    // persisted under some other made-up key.
    @Test
    void frigateDetectionWithNoIdIsSkipped() throws Exception {
        deliver("cabin/camera/events", """
            {"type":"new","after":{"camera":"driveway","label":"person","score":0.5}}
            """);

        assertTrue(eventService.recent("driveway", 10, Instant.now().minusSeconds(60)).isEmpty(),
            "a detection with no Frigate event id must not be persisted");
    }

    // 2026-08-15: this is the actual bug the whole reconciliation change
    // exists to fix -- the same underlying Frigate detection re-reported
    // (e.g. hasClip flips from false to true once encoding finishes) must
    // update the same row, not create a duplicate or get silently dropped.
    @Test
    void repeatedFrigateDetectionUpsertsTheSameRowInsteadOfDuplicating() throws Exception {
        deliver("cabin/camera/events", """
            {"type":"new","after":{"id":"1723000002.0-cc","camera":"driveway","label":"person","score":0.5,"has_clip":false}}
            """);
        deliver("cabin/camera/events", """
            {"type":"end","after":{"id":"1723000002.0-cc","camera":"driveway","label":"person","score":0.5,"has_clip":true}}
            """);

        List<CabinEvent> events = eventService.recent("driveway", 10, Instant.now().minusSeconds(60));
        assertEquals(1, events.size(), "the second message must update the existing row, not add a second one");
        assertEquals(Boolean.TRUE, events.get(0).payload().get("hasClip"),
            "the later, more complete report (hasClip=true) must win, not the first-seen one");
    }

    @Test
    void singlePersonAtCabinDerivesAtCabin() throws Exception {
        deliver("cabin/presence/nate", "home");

        assertEquals(PresenceProfile.AT_CABIN, presenceService.get());
        assertTrue(presenceService.isAutoDerived());
    }

    @Test
    void singlePersonAtHomeDerivesAtHome() throws Exception {
        // Not cabin-only by design -- see PresenceSignalRegistry's comment.
        // home-hub isn't deployed yet, but the topic/derivation logic
        // itself makes no cabin-specific assumption.
        deliver("home/presence/emma", "home");

        assertEquals(PresenceProfile.AT_HOME, presenceService.get());
    }

    @Test
    void onePersonAtEachLocationSimultaneouslyDerivesBothOccupied() throws Exception {
        deliver("cabin/presence/nate", "home");
        deliver("home/presence/emma", "home");

        assertEquals(PresenceProfile.BOTH_OCCUPIED, presenceService.get(),
            "one person at cabin AND a different person at home, simultaneously, must read as Both Occupied");
    }

    @Test
    void everyoneLeavingDerivesAway() throws Exception {
        deliver("cabin/presence/nate", "home");
        deliver("cabin/presence/nate", "not_home");

        assertEquals(PresenceProfile.AWAY, presenceService.get());
    }

    @Test
    void secondPersonArrivingAtSameLocationStaysAtThatLocation() throws Exception {
        // Two people, one location -- must not require exactly one person
        // per location, or double-count into some other state.
        deliver("cabin/presence/nate", "home");
        deliver("cabin/presence/emma", "home");

        assertEquals(PresenceProfile.AT_CABIN, presenceService.get());

        deliver("cabin/presence/nate", "not_home");
        assertEquals(PresenceProfile.AT_CABIN, presenceService.get(),
            "emma is still at cabin -- one person leaving must not clear the whole location");
    }

    @Test
    void manualOverrideIsSupersededByTheNextRealSignal() throws Exception {
        presenceService.set(PresenceProfile.AWAY); // manual override, e.g. no signal configured yet
        assertFalse(presenceService.isAutoDerived());

        deliver("cabin/presence/nate", "home");

        assertEquals(PresenceProfile.AT_CABIN, presenceService.get(),
            "a real signal must win over a stale manual override, not be silently ignored");
        assertTrue(presenceService.isAutoDerived());
    }

    @Test
    void presenceTopicIsNotMisroutedThroughTheJsonDeviceHandler() throws Exception {
        // {location}/presence/{personId} is plain text ("home"/"not_home"),
        // not JSON -- must be handled before the generic JSON-parse
        // fallback, or every presence message would throw and get
        // silently swallowed by messageArrived's catch block.
        deliver("cabin/presence/nate", "home");

        assertEquals(1, presenceSignalRegistry.all().size());
        assertNull(registry.get("nate"), "a presence signal must never register a device");
    }

    @Test
    void armedAwayOnRecordsArmedForThatLocation() throws Exception {
        deliver("cabin/security/armed_away", "ON");

        assertTrue(securityStateRegistry.get("cabin").orElseThrow().armed());
    }

    @Test
    void armedAwayOffRecordsDisarmedForThatLocation() throws Exception {
        deliver("cabin/security/armed_away", "OFF");

        assertFalse(securityStateRegistry.get("cabin").orElseThrow().armed());
    }

    @Test
    void armedStateIsLocationAgnosticNotHardcodedToCabin() throws Exception {
        // home-hub isn't deployed yet, but this must still work today for
        // whatever location actually publishes -- see this class's own
        // javadoc on the +/security/armed_away subscription.
        deliver("home/security/armed_away", "ON");

        assertTrue(securityStateRegistry.get("home").orElseThrow().armed());
        assertTrue(securityStateRegistry.get("cabin").isEmpty(),
            "a signal for one location must not be recorded against a different one");
    }

    @Test
    void armedTopicIsNotMisroutedThroughTheJsonDeviceHandler() throws Exception {
        // Plain text ("ON"/"OFF"), not JSON -- same reasoning as presence.
        deliver("cabin/security/armed_away", "ON");

        assertNull(registry.get("armed_away"), "an armed-state signal must never register a device");
    }
}
