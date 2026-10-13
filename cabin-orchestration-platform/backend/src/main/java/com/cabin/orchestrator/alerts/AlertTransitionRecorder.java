package com.cabin.orchestrator.alerts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * W-64. ActiveAlertService is stateless on purpose (an active alert must
 * vanish the moment its evidence does), so nothing remembers that a
 * device missed its check-in nine times this week. This job gives that
 * memory a home without changing the service: each cycle it diffs the
 * current snapshot against the keys the log says are open and appends
 * RAISED / RESOLVED rows.
 *
 * Open state is derived from the log itself (the latest RAISED/RESOLVED
 * row per key), so a restart neither forgets nor double-counts. Two guards
 * keep an empty registry from inventing churn: RESOLVED is not written
 * during a startup grace period (adapters repopulate DeviceRegistry after
 * boot), and a key must be absent for two consecutive cycles before it
 * counts as resolved.
 */
@Component
public class AlertTransitionRecorder {

    private static final Logger log = LoggerFactory.getLogger(AlertTransitionRecorder.class);

    @Value("${cabin.alerts.history.enabled:true}")
    private boolean enabled;

    @Value("${cabin.alerts.history.startupGraceMillis:180000}")
    private long startupGraceMillis;

    private final ActiveAlertService activeAlerts;
    private final JdbcAlertHistoryStore store;
    private final Instant startedAt = Instant.now();
    private final Map<String, Integer> absentCycles = new HashMap<>();

    public AlertTransitionRecorder(ActiveAlertService activeAlerts, JdbcAlertHistoryStore store) {
        this.activeAlerts = activeAlerts;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${cabin.alerts.history.intervalMillis:60000}", initialDelayString = "${cabin.alerts.history.intervalMillis:60000}")
    public void scheduledRecord() {
        if (!enabled) return;
        try {
            recordTransitions();
        } catch (Exception e) {
            log.warn("Alert transition recording skipped this cycle: {}", e.getMessage());
        }
    }

    public void recordTransitions() {
        Instant now = Instant.now();
        Map<String, ActiveAlert> current = new HashMap<>();
        for (ActiveAlert a : activeAlerts.snapshot().alerts()) current.put(a.alertId(), a);

        Set<String> open = new HashSet<>();
        store.latestTransitionByKey().forEach((key, action) -> { if ("RAISED".equals(action)) open.add(key); });

        for (ActiveAlert a : current.values()) {
            absentCycles.remove(a.alertId());
            if (open.contains(a.alertId())) continue;
            AlertKeys.Parsed p = AlertKeys.parse(a.alertId());
            store.append(new AlertHistoryEntry(null, now, a.alertId(), "device", a.sourceDeviceId(),
                a.condition() != null ? a.condition() : p.condition(), null, a.severity(), "RAISED", null, null, a.title()));
        }

        boolean inGrace = Duration.between(startedAt, now).toMillis() < startupGraceMillis;
        for (String key : open) {
            if (current.containsKey(key)) continue;
            if (inGrace) continue;
            int missing = absentCycles.merge(key, 1, Integer::sum);
            if (missing < 2) continue;
            absentCycles.remove(key);
            AlertKeys.Parsed p = AlertKeys.parse(key);
            store.append(new AlertHistoryEntry(null, now, key, "device", p.deviceId(), p.condition(), null, null,
                "RESOLVED", null, null, null));
        }
    }
}
