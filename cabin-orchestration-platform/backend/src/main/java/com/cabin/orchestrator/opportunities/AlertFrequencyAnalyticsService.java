package com.cabin.orchestrator.opportunities;

import com.cabin.orchestrator.alerts.AlertHistoryCount;
import com.cabin.orchestrator.alerts.AlertKeys;
import com.cabin.orchestrator.alerts.JdbcAlertHistoryStore;
import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCategory;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.events.AlertEventCount;
import com.cabin.orchestrator.events.CabinEventService;
import com.cabin.orchestrator.opportunities.AlertRecommendations.AlertNoise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * W-57. Turns "this alert keeps happening" into a NOISY_ALERT opportunity per
 * device, with the likely places to look (AlertRecommendations). Same
 * config-driven-cron + enabled-flag convention, and the same never-overwrite
 * rule, as OptimizationAnalyticsService: it only creates a row or refreshes
 * the evidence of one still OPEN/ACKNOWLEDGED, and never resolves anything.
 *
 * What counts. Over a rolling window (default 7 days): times an alert was
 * raised (device conditions from alert_history, automation alerts from
 * cabin_event) and times a person silenced it (IGNORED/SNOOZED). A device is
 * flagged when any one of its alerts reaches minRaises or minSilences. Being
 * silenced repeatedly is its own signal: it means a person already decided
 * the alert is noise.
 *
 * Resolution is respected. Once an admin resolves a device's opportunity,
 * only events after that moment count toward the next one, so the same
 * already-addressed events still inside the window cannot reopen it an hour
 * later.
 */
@Service
public class AlertFrequencyAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(AlertFrequencyAnalyticsService.class);
    private static final List<String> AUTOMATION_EVENT_TYPES = List.of("AUTOMATION_ALERT", "WORKFLOW_ACTION", "WORKFLOW_UNCONFIRMED");

    @Value("${cabin.opportunities.alertNoise.enabled:true}")
    private boolean enabled;

    @Value("${cabin.opportunities.alertNoise.windowDays:7}")
    private int windowDays;

    @Value("${cabin.opportunities.alertNoise.minRaises:5}")
    private int minRaises;

    @Value("${cabin.opportunities.alertNoise.minSilences:3}")
    private int minSilences;

    private final JdbcAlertHistoryStore history;
    private final CabinEventService events;
    private final JdbcOptimizationOpportunityStore store;
    private final DeviceRegistry registry;

    public AlertFrequencyAnalyticsService(JdbcAlertHistoryStore history, CabinEventService events,
                                           JdbcOptimizationOpportunityStore store, DeviceRegistry registry) {
        this.history = history;
        this.events = events;
        this.store = store;
        this.registry = registry;
    }

    @Scheduled(cron = "${cabin.opportunities.alertNoise.cron:0 20 * * * *}")
    public void scanForNoisyAlerts() {
        if (!enabled) return;
        Instant since = Instant.now().minus(Duration.ofDays(windowDays));
        for (Map.Entry<String, Map<String, Tally>> device : aggregate(since, null).entrySet()) {
            try {
                evaluate(device.getKey(), device.getValue(), since);
            } catch (Exception e) {
                log.warn("Skipping noisy-alert check for {}: {}", device.getKey(), e.getMessage());
            }
        }
    }

    private static final class Tally {
        final String kind;
        final String ruleId;
        final String condition;
        long raised;
        long silenced;
        Instant firstAt;
        Instant lastAt;

        Tally(String kind, String ruleId, String condition) {
            this.kind = kind;
            this.ruleId = ruleId;
            this.condition = condition;
        }

        void see(Instant first, Instant last) {
            if (first != null && (firstAt == null || first.isBefore(firstAt))) firstAt = first;
            if (last != null && (lastAt == null || last.isAfter(lastAt))) lastAt = last;
        }
    }

    /** deviceId -> alertKey -> tally. The deviceId filter is nullable (null = every device). */
    private Map<String, Map<String, Tally>> aggregate(Instant since, String deviceId) {
        Map<String, Map<String, Tally>> byDevice = new HashMap<>();

        for (AlertHistoryCount row : history.countsSince(since, deviceId)) {
            if (row.deviceId() == null) continue;
            boolean raised = "RAISED".equals(row.action());
            boolean silenced = "IGNORED".equals(row.action()) || "SNOOZED".equals(row.action());
            if (!raised && !silenced) continue;
            AlertKeys.Parsed p = AlertKeys.parse(row.alertKey());
            Tally t = byDevice.computeIfAbsent(row.deviceId(), d -> new HashMap<>())
                .computeIfAbsent(row.alertKey(), k -> new Tally(row.kind(), p.ruleId(), row.condition()));
            if (raised) t.raised += row.count();
            if (silenced) t.silenced += row.count();
            t.see(row.firstAt(), row.lastAt());
        }

        for (AlertEventCount row : events.alertEventCounts(AUTOMATION_EVENT_TYPES, since, deviceId)) {
            String key = AlertKeys.automation(row.ruleId(), row.deviceId());
            String rule = AlertKeys.parse(key).ruleId();
            Tally t = byDevice.computeIfAbsent(row.deviceId(), d -> new HashMap<>())
                .computeIfAbsent(key, k -> new Tally("automation", rule, null));
            t.raised += row.count();
            t.see(row.firstAt(), row.lastAt());
        }
        return byDevice;
    }

    void evaluate(String deviceId, Map<String, Tally> tallies, Instant windowStart) {
        Optional<Instant> resolvedAt = store.latestResolvedAt(deviceId, OpportunityType.NOISY_ALERT);
        if (resolvedAt.isPresent() && resolvedAt.get().isAfter(windowStart)) {
            tallies = aggregate(resolvedAt.get(), deviceId).getOrDefault(deviceId, Map.of());
        }

        boolean safetyDevice = isSafetyDevice(deviceId);
        List<Map<String, Object>> noisy = new ArrayList<>();
        tallies.entrySet().stream()
            .filter(e -> e.getValue().raised >= minRaises || e.getValue().silenced >= minSilences)
            .sorted(Comparator.<Map.Entry<String, Tally>>comparingLong(e -> e.getValue().raised).reversed()
                .thenComparing(Map.Entry::getKey))
            .forEach(e -> {
                Tally t = e.getValue();
                AlertNoise noise = new AlertNoise(e.getKey(), t.kind, t.ruleId, t.condition, deviceId,
                    t.raised, t.silenced, t.firstAt, t.lastAt);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("alertKey", e.getKey());
                item.put("kind", t.kind);
                item.put("ruleId", t.ruleId);
                item.put("condition", t.condition);
                item.put("raised", t.raised);
                item.put("silenced", t.silenced);
                item.put("firstAt", t.firstAt == null ? null : t.firstAt.toString());
                item.put("lastAt", t.lastAt == null ? null : t.lastAt.toString());
                item.put("safetyClass", AlertRecommendations.isSafetyClass(noise, safetyDevice));
                item.put("recommendations", AlertRecommendations.forAlert(noise, safetyDevice, minSilences));
                noisy.add(item);
            });
        if (noisy.isEmpty()) return;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("windowDays", windowDays);
        evidence.put("minRaises", minRaises);
        evidence.put("minSilences", minSilences);
        evidence.put("alerts", noisy);

        Optional<OptimizationOpportunity> existing = store.findOpenOrAcknowledged(deviceId, OpportunityType.NOISY_ALERT);
        String id = existing.map(OptimizationOpportunity::id).orElseGet(() -> UUID.randomUUID().toString());
        OpportunityStatus status = existing.map(OptimizationOpportunity::status).orElse(OpportunityStatus.OPEN);
        Instant detectedAt = existing.map(OptimizationOpportunity::detectedAt).orElseGet(Instant::now);
        store.save(new OptimizationOpportunity(id, OpportunityType.NOISY_ALERT, deviceId, detectedAt, evidence, status, null));
    }

    private boolean isSafetyDevice(String deviceId) {
        DeviceStatus status = registry.get(deviceId);
        return status != null && status.type() != null && status.type().category() == DeviceCategory.SAFETY;
    }
}
