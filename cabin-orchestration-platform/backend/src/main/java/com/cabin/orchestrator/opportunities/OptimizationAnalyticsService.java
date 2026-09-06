package com.cabin.orchestrator.opportunities;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.events.CabinEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 5 WSJF #1 (r5 handover, "Optimization Opportunities"). Same
 * config-driven-cron + enabled-flag convention as TelemetryArchivalService.
 *
 * The handover's own starter rule ("ENERGY_IDLE: a POWER_METER device
 * drawing power during unoccupied hours = waste") was not built as
 * specified -- verified against the real device inventory before writing
 * any code, not guessed past. Two real problems, either one alone would be
 * disqualifying:
 *
 * 1. "Unoccupied hours" as a historical, queryable signal does not exist.
 *    PresenceService/PresenceSignalRegistry are current-only and not
 *    persisted (a restart loses all history) -- there is no way to ask
 *    "was anyone home at time T" for any T in the past.
 * 2. The only two real, live POWER_METER devices are zigbee_heater_mech_room
 *    (the mechanical-room freeze-prevention heater) and
 *    zigbee_smart_switch_breaker_box. The heater is *supposed* to draw
 *    power while the cabin is unoccupied in cold weather -- that is
 *    exactly its job. Flagging that as "wasted energy" in a cabin with a
 *    documented freeze/flood history would be actively dangerous
 *    messaging, not a nitpick.
 *
 * POWER_DRAW_ANOMALY replaces it: a device that has drawn non-negligible
 * power continuously, with no dip back near zero, for longer than
 * continuousDrawHours. This is self-relative (compares a device only
 * against its own recent telemetry, never against an occupancy judgment)
 * and honestly framed as "worth a look," never "this is waste" -- true
 * and useful for every device type, including the heater (a stuck
 * contactor or a sensor fault keeping it on unnecessarily is exactly the
 * kind of thing this should surface). See OpportunityType's own doc.
 *
 * The job only ever creates or refreshes a row -- it never resolves one.
 * An admin's ACKNOWLEDGED/RESOLVED decision is never silently overwritten
 * by the next scan; only a still-open condition's evidence gets refreshed
 * in place (same id, original detectedAt preserved).
 */
@Service
public class OptimizationAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationAnalyticsService.class);
    private static final String POWER_FIELD = "power";

    @Value("${cabin.opportunities.enabled:true}")
    private boolean enabled;

    @Value("${cabin.opportunities.continuousDrawHours:48}")
    private int continuousDrawHours;

    @Value("${cabin.opportunities.lookbackDays:30}")
    private int lookbackDays;

    @Value("${cabin.opportunities.offThresholdWatts:1.0}")
    private double offThresholdWatts;

    private final DeviceRegistry registry;
    private final CabinEventService eventService;
    private final JdbcOptimizationOpportunityStore store;

    public OptimizationAnalyticsService(DeviceRegistry registry, CabinEventService eventService,
                                         JdbcOptimizationOpportunityStore store) {
        this.registry = registry;
        this.eventService = eventService;
        this.store = store;
    }

    @Scheduled(cron = "${cabin.opportunities.cron:0 0 * * * *}")
    public void scanForOpportunities() {
        if (!enabled) return;
        for (DeviceStatus status : registry.inScope()) {
            if (status.type() != DeviceType.POWER_METER) continue;
            try {
                checkPowerDrawAnomaly(status);
            } catch (Exception e) {
                log.warn("Skipping power-draw-anomaly check for {}: {}", status.deviceId(), e.getMessage());
            }
        }
    }

    void checkPowerDrawAnomaly(DeviceStatus status) {
        String deviceId = status.deviceId();
        Instant since = Instant.now().minus(Duration.ofDays(lookbackDays));

        Optional<Instant> mostRecentOff = eventService.mostRecentAtOrBelow(deviceId, POWER_FIELD, offThresholdWatts, since);
        Instant onSince;
        boolean neverObservedOffInWindow = mostRecentOff.isEmpty();
        if (mostRecentOff.isPresent()) {
            onSince = mostRecentOff.get();
        } else {
            Optional<Instant> earliest = eventService.earliestInWindow(deviceId, POWER_FIELD, since);
            if (earliest.isEmpty()) return; // no telemetry for this field at all yet
            onSince = earliest.get();
        }

        Duration continuousDuration = Duration.between(onSince, Instant.now());
        if (continuousDuration.toHours() < continuousDrawHours) return;

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("continuousSince", onSince.toString());
        evidence.put("continuousHours", continuousDuration.toHours());
        evidence.put("currentPowerWatts", status.attributes().get(POWER_FIELD));
        evidence.put("neverObservedOffInWindow", neverObservedOffInWindow);

        Optional<OptimizationOpportunity> existing = store.findOpenOrAcknowledged(deviceId, OpportunityType.POWER_DRAW_ANOMALY);
        String id = existing.map(OptimizationOpportunity::id).orElseGet(() -> UUID.randomUUID().toString());
        OpportunityStatus statusToSave = existing.map(OptimizationOpportunity::status).orElse(OpportunityStatus.OPEN);
        Instant detectedAt = existing.map(OptimizationOpportunity::detectedAt).orElseGet(Instant::now);

        store.save(new OptimizationOpportunity(id, OpportunityType.POWER_DRAW_ANOMALY, deviceId,
            detectedAt, evidence, statusToSave, null));
    }
}
