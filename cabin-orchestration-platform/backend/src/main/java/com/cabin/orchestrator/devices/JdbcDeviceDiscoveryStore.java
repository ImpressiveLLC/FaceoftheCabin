package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceDiscoveryResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Persists device self-discovery results independently from the live
 * `device` table (see JdbcDeviceLifecycleStore) -- a discovery run is a
 * proposal, never authoritative, until a person explicitly applies
 * selected fields through DeviceRegistry.
 */
@Repository
public class JdbcDeviceDiscoveryStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcDeviceDiscoveryStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcDeviceDiscoveryStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_discovery_result (
              run_id       TEXT PRIMARY KEY,
              device_id    TEXT NOT NULL,
              requested_at TIMESTAMPTZ NOT NULL,
              applied_at   TIMESTAMPTZ,
              result       JSONB NOT NULL
            )""");
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS device_discovery_result_device_idx
            ON device_discovery_result (device_id, requested_at DESC)""");
    }

    public void save(DeviceDiscoveryResult result) {
        jdbc.update("""
            INSERT INTO device_discovery_result (run_id, device_id, requested_at, applied_at, result)
            VALUES (?, ?, ?, ?, ?::jsonb)
            ON CONFLICT (run_id) DO UPDATE SET
              applied_at = EXCLUDED.applied_at,
              result = EXCLUDED.result
            """,
            result.runId(), result.deviceId(),
            Timestamp.from(result.requestedAt()),
            result.appliedAt() == null ? null : Timestamp.from(result.appliedAt()),
            toJson(result));
    }

    public Optional<DeviceDiscoveryResult> latestFor(String deviceId) {
        List<DeviceDiscoveryResult> rows = jdbc.query("""
            SELECT result::text AS result FROM device_discovery_result
            WHERE device_id = ? ORDER BY requested_at DESC LIMIT 1
            """, (rs, rowNum) -> fromJson(rs.getString("result")), deviceId);
        return rows.stream().filter(Objects::nonNull).findFirst();
    }

    public void markApplied(String runId, Instant appliedAt) {
        // The `applied_at` SQL column and the JSONB `result` blob's own
        // appliedAt field must stay in sync -- latestFor() deserializes the
        // blob, not the column, so a raw column-only UPDATE here would
        // silently never be visible to a caller. Round-trip through save()
        // so both are rewritten from one updated record.
        List<DeviceDiscoveryResult> rows = jdbc.query("""
            SELECT result::text AS result FROM device_discovery_result WHERE run_id = ?
            """, (rs, rowNum) -> fromJson(rs.getString("result")), runId);
        rows.stream().filter(Objects::nonNull).findFirst().ifPresent(existing ->
            save(new DeviceDiscoveryResult(existing.runId(), existing.deviceId(),
                existing.requestedAt(), appliedAt, existing.matches())));
    }

    private String toJson(DeviceDiscoveryResult result) {
        try {
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize discovery result", e);
        }
    }

    private DeviceDiscoveryResult fromJson(String json) {
        try {
            return mapper.readValue(json, DeviceDiscoveryResult.class);
        } catch (Exception e) {
            // A malformed or future-version row must not prevent the rest
            // of the app from working -- same defensive pattern as
            // JdbcDeviceLifecycleStore.loadAll().
            log.warn("Skipping invalid persisted discovery result: {}", e.getMessage());
            return null;
        }
    }
}
