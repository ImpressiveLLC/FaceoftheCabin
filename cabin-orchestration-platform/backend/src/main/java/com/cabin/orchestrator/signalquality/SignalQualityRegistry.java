package com.cabin.orchestrator.signalquality;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PROTOTYPE, 2026-08-08 — evaluating whether Zigbee LQI (link quality)
 * fluctuation on the existing mesh is a usable presence/obstruction
 * signal, before investing in new WiFi hardware for the same goal.
 *
 * Why Zigbee LQI instead of WiFi RSSI: this instance's Zigbee mesh
 * already has three wired, always-on router-role devices spread across
 * the building (heater_mech_room, main_water_valve,
 * smart_switch_breaker_box — see docs/ontology.yaml), relaying nearby
 * battery end-devices, with Zigbee2MqttAdapter already parsing
 * `linkquality` out of every device message (see deriveState()'s own
 * check for it) -- it was just never trended or used for anything.
 * WiFi RSSI would need new HA-integrable AP hardware first (see
 * grafana/dashboards/22019-wifi-scan/README.md); this needs nothing new.
 *
 * Deliberately NOT wired into AlertSeverityClassifier, the toolbar, or
 * any real alert yet -- this is the "observe and evaluate" stage the
 * user explicitly asked for, not a shipped detection feature. See
 * ROADMAP.md's WiFi RSSI presence-detection item.
 */
@Component
public class SignalQualityRegistry {

    /**
     * How far below its own rolling baseline a reading has to fall to be
     * flagged. 0.7 = a 30% drop. Explicitly a placeholder, not a tuned
     * value -- Zigbee LQI is noisy for reasons that have nothing to do
     * with anyone being present (interference, battery voltage sag,
     * routing-path changes), and this threshold needs real-world
     * evaluation against actual data before anything downstream trusts
     * it as a presence signal.
     */
    static final double ANOMALY_DROP_RATIO = 0.7;

    /** Minimum readings (including the latest) before a baseline is meaningful at all. */
    static final int MIN_SAMPLES_FOR_BASELINE = 5;

    private static final int HISTORY_SIZE = 20;

    public record Reading(int lqi, Instant at) {}

    /**
     * baseline is null when there aren't enough samples yet
     * (MIN_SAMPLES_FOR_BASELINE) -- callers must treat that as "not
     * enough data," never as "baseline is zero."
     */
    public record Assessment(int current, Double baseline, boolean anomalous, int sampleCount) {}

    private final Map<String, Deque<Reading>> history = new ConcurrentHashMap<>();

    public synchronized void record(String deviceId, int lqi) {
        Deque<Reading> readings = history.computeIfAbsent(deviceId, k -> new ArrayDeque<>());
        readings.addLast(new Reading(lqi, Instant.now()));
        while (readings.size() > HISTORY_SIZE) readings.removeFirst();
    }

    public synchronized Optional<Assessment> assess(String deviceId) {
        Deque<Reading> readings = history.get(deviceId);
        if (readings == null || readings.isEmpty()) return Optional.empty();

        List<Reading> all = new ArrayList<>(readings);
        Reading latest = all.get(all.size() - 1);

        if (all.size() < MIN_SAMPLES_FOR_BASELINE) {
            return Optional.of(new Assessment(latest.lqi(), null, false, all.size()));
        }

        // Baseline excludes the latest reading -- otherwise a genuine drop
        // would drag its own baseline down with it and could never be
        // detected as anomalous.
        double baseline = all.subList(0, all.size() - 1).stream()
            .mapToInt(Reading::lqi).average().orElse(latest.lqi());
        boolean anomalous = baseline > 0 && latest.lqi() < baseline * ANOMALY_DROP_RATIO;

        return Optional.of(new Assessment(latest.lqi(), baseline, anomalous, all.size()));
    }

    public Map<String, Assessment> allAssessments() {
        Map<String, Assessment> out = new LinkedHashMap<>();
        for (String deviceId : history.keySet()) {
            assess(deviceId).ifPresent(a -> out.put(deviceId, a));
        }
        return out;
    }
}
