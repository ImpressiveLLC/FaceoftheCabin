package com.cabin.orchestrator.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Reads the most recent stored occupancy value for a sensor. Never throws: ingest must not depend on it. */
@Component
public class JdbcOccupancyHistory implements OccupancyHistory {

    private final JdbcTemplate jdbc;

    public JdbcOccupancyHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Boolean> lastKnown(String deviceId) {
        try {
            List<String> rows = jdbc.queryForList("""
                SELECT payload->>'occupancy'
                FROM cabin_event
                WHERE device_id = ? AND event_type = 'TELEMETRY' AND jsonb_exists(payload, 'occupancy')
                ORDER BY time DESC LIMIT 1""", String.class, deviceId);
            if (rows.isEmpty()) return Optional.empty();
            return switch (rows.get(0)) {
                case "true" -> Optional.of(true);
                case "false" -> Optional.of(false);
                default -> Optional.empty();
            };
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
