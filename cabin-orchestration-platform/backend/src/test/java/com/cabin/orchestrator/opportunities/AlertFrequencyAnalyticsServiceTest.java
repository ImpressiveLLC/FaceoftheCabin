package com.cabin.orchestrator.opportunities;

import com.cabin.orchestrator.alerts.AlertHistoryEntry;
import com.cabin.orchestrator.alerts.JdbcAlertHistoryStore;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AlertFrequencyAnalyticsServiceTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String DEVICE = "z2m-temp_kitchen";
    private static final String KEY = "device:" + DEVICE + ":missed-checkin";

    private CabinEventService eventService;
    private JdbcAlertHistoryStore history;
    private JdbcOptimizationOpportunityStore store;
    private DeviceRegistry registry;
    private AlertFrequencyAnalyticsService job;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new SimpleDriverDataSource(
            new org.postgresql.Driver(), postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
        jdbc.execute("DROP TABLE IF EXISTS cabin_event");
        jdbc.execute("DROP TABLE IF EXISTS device_reporting_relationship");
        jdbc.execute("DROP TABLE IF EXISTS optimization_opportunity");
        jdbc.execute("DROP TABLE IF EXISTS alert_history");

        eventService = new CabinEventService(jdbc);
        history = new JdbcAlertHistoryStore(jdbc);
        store = new JdbcOptimizationOpportunityStore(jdbc, new ObjectMapper().findAndRegisterModules());
        registry = new DeviceRegistry(List.of());
        job = new AlertFrequencyAnalyticsService(history, eventService, store, registry);
        ReflectionTestUtils.setField(job, "enabled", true);
        ReflectionTestUtils.setField(job, "windowDays", 7);
        ReflectionTestUtils.setField(job, "minRaises", 5);
        ReflectionTestUtils.setField(job, "minSilences", 3);
    }

    private static Instant hoursAgo(long h) {
        return Instant.now().minusSeconds(h * 3600);
    }

    private void raised(String key, String deviceId, String condition, int times, long firstHoursAgo) {
        for (int i = 0; i < times; i++) {
            history.append(new AlertHistoryEntry(null, hoursAgo(firstHoursAgo - i), key, "device", deviceId, condition, null,
                "WARN", "RAISED", null, null, "title"));
        }
    }

    private void silenced(String key, String deviceId, String action, int times) {
        for (int i = 0; i < times; i++) {
            history.append(AlertHistoryEntry.ofAcknowledgment(key, action, "nate@example.com",
                "SNOOZED".equals(action) ? Instant.now().plusSeconds(3600) : null, hoursAgo(10 - i)));
        }
    }

    private void automationEvents(String ruleId, String deviceId, int times, long firstHoursAgo, String eventType) {
        for (int i = 0; i < times; i++) {
            eventService.save(new CabinEvent(UUID.randomUUID().toString(), deviceId, eventType, "WARN",
                hoursAgo(firstHoursAgo - i), Map.of("ruleId", ruleId, "see", "something")));
        }
    }

    private List<OptimizationOpportunity> noisy() {
        return store.findAll(null).stream().filter(o -> o.opportunityType() == OpportunityType.NOISY_ALERT).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> alertsOf(OptimizationOpportunity o) {
        return (List<Map<String, Object>>) o.evidence().get("alerts");
    }

    @Test
    void aDeviceConditionRaisedOftenBecomesAnOpportunityWithAdvice() {
        raised(KEY, DEVICE, "MISSED_CHECKIN", 5, 50);

        job.scanForNoisyAlerts();

        assertThat(noisy()).hasSize(1);
        OptimizationOpportunity o = noisy().get(0);
        assertThat(o.deviceId()).isEqualTo(DEVICE);
        assertThat(o.status()).isEqualTo(OpportunityStatus.OPEN);
        Map<String, Object> alert = alertsOf(o).get(0);
        assertThat(alert.get("alertKey")).isEqualTo(KEY);
        assertThat(((Number) alert.get("raised")).intValue()).isEqualTo(5);
        assertThat(String.valueOf(alert.get("recommendations")).toLowerCase()).contains("check-in window");
        assertThat(alert.get("safetyClass")).isEqualTo(false);
    }

    @Test
    void belowBothThresholdsNothingIsFlagged() {
        raised(KEY, DEVICE, "MISSED_CHECKIN", 4, 50);
        silenced(KEY, DEVICE, "IGNORED", 2);

        job.scanForNoisyAlerts();

        assertThat(noisy()).isEmpty();
    }

    @Test
    void anAlertOlderThanTheWindowIsNotCounted() {
        raised(KEY, DEVICE, "MISSED_CHECKIN", 5, 24 * 20);

        job.scanForNoisyAlerts();

        assertThat(noisy()).isEmpty();
    }

    @Test
    void automationAlertsInTheEventLogAreCountedAndFreezeRiskIsTreatedAsSafety() {
        automationEvents("FREEZE_RISK", "z2m-temp_outside_lowest", 5, 40, "AUTOMATION_ALERT");

        job.scanForNoisyAlerts();

        assertThat(noisy()).hasSize(1);
        Map<String, Object> alert = alertsOf(noisy().get(0)).get(0);
        assertThat(alert.get("alertKey")).isEqualTo("automation:FREEZE_RISK:z2m-temp_outside_lowest");
        assertThat(alert.get("safetyClass")).isEqualTo(true);
        assertThat(String.valueOf(alert.get("recommendations")).toLowerCase()).contains("not a fix");
    }

    @Test
    void everyUnconfirmedCommandAlertCountsAsTheSameAlertDespiteItsExecutionId() {
        for (int i = 0; i < 5; i++) {
            automationEvents("WORKFLOW_UNCONFIRMED_exec-" + i, "z2m-main_water_valve", 1, 30 - i, "WORKFLOW_UNCONFIRMED");
        }

        job.scanForNoisyAlerts();

        assertThat(noisy()).hasSize(1);
        assertThat(alertsOf(noisy().get(0))).hasSize(1);
        assertThat(((Number) alertsOf(noisy().get(0)).get(0).get("raised")).intValue()).isEqualTo(5);
    }

    @Test
    void beingSilencedRepeatedlyIsEnoughOnItsOwn() {
        raised(KEY, DEVICE, "MISSED_CHECKIN", 1, 30);
        silenced(KEY, DEVICE, "SNOOZED", 3);

        job.scanForNoisyAlerts();

        assertThat(noisy()).hasSize(1);
        Map<String, Object> alert = alertsOf(noisy().get(0)).get(0);
        assertThat(((Number) alert.get("silenced")).intValue()).isEqualTo(3);
        assertThat(String.valueOf(alert.get("recommendations")).toLowerCase()).contains("silenced 3 times", "retire");
    }

    @Test
    void anAlertOnASafetyCategoryDeviceIsSafetyClassWhateverItsCondition() {
        registry.registerDescriptor(new DeviceDescriptor("z2m-leak", "Leak", DeviceType.WATER_LEAK_SENSOR,
            Set.of(DeviceCapability.TELEMETRY), "mqtt", "z2m-leak", true, "cabin"));
        registry.update(new DeviceStatus("z2m-leak", DeviceType.WATER_LEAK_SENSOR, "Leak", "ONLINE", Instant.now(), Map.of(), "cabin"));
        raised("device:z2m-leak:missed-checkin", "z2m-leak", "MISSED_CHECKIN", 5, 40);

        job.scanForNoisyAlerts();

        Map<String, Object> alert = alertsOf(noisy().get(0)).get(0);
        assertThat(alert.get("safetyClass")).isEqualTo(true);
        assertThat(String.valueOf(alert.get("recommendations")).toLowerCase()).doesNotContain("check-in window");
    }

    @Test
    void aSecondScanRefreshesEvidenceButKeepsTheRowTheAdminAlreadyAcknowledged() {
        raised(KEY, DEVICE, "MISSED_CHECKIN", 5, 50);
        job.scanForNoisyAlerts();
        OptimizationOpportunity first = noisy().get(0);
        store.save(new OptimizationOpportunity(first.id(), first.opportunityType(), first.deviceId(), first.detectedAt(),
            first.evidence(), OpportunityStatus.ACKNOWLEDGED, null));

        raised(KEY, DEVICE, "MISSED_CHECKIN", 2, 10);
        job.scanForNoisyAlerts();

        assertThat(noisy()).hasSize(1);
        OptimizationOpportunity again = noisy().get(0);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.status()).isEqualTo(OpportunityStatus.ACKNOWLEDGED);
        assertThat(again.detectedAt()).isEqualTo(first.detectedAt());
        assertThat(((Number) alertsOf(again).get(0).get("raised")).intValue()).isEqualTo(7);
    }

    @Test
    void aResolvedOpportunityIsNotReopenedByTheSameEventsStillInTheWindow() {
        raised(KEY, DEVICE, "MISSED_CHECKIN", 5, 60);
        store.save(new OptimizationOpportunity(UUID.randomUUID().toString(), OpportunityType.NOISY_ALERT, DEVICE,
            hoursAgo(40), Map.of(), OpportunityStatus.RESOLVED, hoursAgo(30)));

        job.scanForNoisyAlerts();

        assertThat(noisy()).hasSize(1);
        assertThat(noisy().get(0).status()).isEqualTo(OpportunityStatus.RESOLVED);
    }

    @Test
    void newNoiseAfterAResolutionOpensAFreshOpportunityCountingOnlyTheNewEvents() {
        raised(KEY, DEVICE, "MISSED_CHECKIN", 5, 60);
        store.save(new OptimizationOpportunity(UUID.randomUUID().toString(), OpportunityType.NOISY_ALERT, DEVICE,
            hoursAgo(40), Map.of(), OpportunityStatus.RESOLVED, hoursAgo(30)));
        raised(KEY, DEVICE, "MISSED_CHECKIN", 5, 20);

        job.scanForNoisyAlerts();

        List<OptimizationOpportunity> open = noisy().stream().filter(o -> o.status() == OpportunityStatus.OPEN).toList();
        assertThat(open).hasSize(1);
        assertThat(((Number) alertsOf(open.get(0)).get(0).get("raised")).intValue()).isEqualTo(5);
    }

    @Test
    void anAutomationEventWithNoDeviceIsSkipped() {
        for (int i = 0; i < 6; i++) {
            eventService.save(new CabinEvent(UUID.randomUUID().toString(), null, "AUTOMATION_ALERT", "WARN",
                hoursAgo(30 - i), Map.of("ruleId", "FREEZE_RISK")));
        }

        job.scanForNoisyAlerts();

        assertThat(noisy()).isEmpty();
    }

    @Test
    void disabledMeansNothingHappens() {
        ReflectionTestUtils.setField(job, "enabled", false);
        raised(KEY, DEVICE, "MISSED_CHECKIN", 9, 50);

        job.scanForNoisyAlerts();

        assertThat(noisy()).isEmpty();
    }
}
