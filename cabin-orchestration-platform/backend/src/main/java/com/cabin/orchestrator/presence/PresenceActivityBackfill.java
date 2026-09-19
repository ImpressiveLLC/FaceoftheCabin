package com.cabin.orchestrator.presence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reconstructs OCCUPANCY_SENSOR_ACTIVATED/CLEARED events from the TELEMETRY
 * history that already exists, so presence-by-day has real data from the day
 * this ships instead of starting empty.
 *
 * <p>Idempotent by construction: it derives the same deterministic event_id
 * {@code OccupancyEdges} does (occ:{device}:{epochMillis}:{A|C}) and inserts
 * with ON CONFLICT DO NOTHING, so running it on every boot is harmless, and a
 * transition the live path already recorded is not duplicated. That also keeps
 * the edge history complete after TelemetryArchivalService ages raw TELEMETRY
 * rows out -- edges written here are discrete events and survive that.
 *
 * <p>A transition is a row whose occupancy differs from the same device's
 * previous occupancy row (LAG). The first row a device has can't be compared,
 * so a leading occupancy=true counts as an ACTIVATED and a leading false is
 * ignored.
 */
@Component
public class PresenceActivityBackfill {

    private static final Logger log = LoggerFactory.getLogger(PresenceActivityBackfill.class);

    private static final String SQL = """
        INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
        SELECT 'occ:' || device_id || ':' || round(extract(epoch from date_trunc('milliseconds', time)) * 1000)::bigint
                   || ':' || CASE WHEN occ THEN 'A' ELSE 'C' END,
               time, device_id,
               CASE WHEN occ THEN 'OCCUPANCY_SENSOR_ACTIVATED' ELSE 'OCCUPANCY_SENSOR_CLEARED' END,
               'INFO',
               jsonb_build_object('sensor', device_id, 'backfilled', true)
        FROM (
            SELECT device_id, time, (payload->>'occupancy') = 'true' AS occ,
                   LAG(payload->>'occupancy') OVER (PARTITION BY device_id ORDER BY time) AS prev
            FROM cabin_event
            WHERE event_type = 'TELEMETRY' AND device_id IS NOT NULL
              AND payload->>'occupancy' IN ('true', 'false')
        ) t
        WHERE (occ AND prev IS DISTINCT FROM 'true') OR (NOT occ AND prev = 'true')
        ON CONFLICT (event_id) DO NOTHING""";

    private final JdbcTemplate jdbc;

    @Value("${cabin.presence.backfill.enabled:true}")
    private boolean enabled;

    public PresenceActivityBackfill(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        // Off the startup thread: it scans the whole TELEMETRY hot tier once.
        Thread t = new Thread(this::run, "presence-activity-backfill");
        t.setDaemon(true);
        t.start();
    }

    /** Returns the number of edge events newly written. Never throws: a failed backfill must not affect startup. */
    public int run() {
        try {
            int n = jdbc.update(SQL);
            log.info("Presence backfill: {} occupancy transition(s) newly recorded from TELEMETRY history", n);
            return n;
        } catch (Exception e) {
            log.warn("Presence backfill failed (will retry next start): {}", e.getMessage());
            return 0;
        }
    }
}
