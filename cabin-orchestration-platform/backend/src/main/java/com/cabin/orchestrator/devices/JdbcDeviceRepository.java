package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Additive real columns on the existing `device` table (issue #30). Row
 * creation (device_id, name, type, capabilities, protocol, config) stays
 * owned by JdbcDeviceLifecycleStore -- this class only ever touches the
 * columns it adds here, so the two repositories can't race or clobber each
 * other's writes.
 */
@Repository
public class JdbcDeviceRepository implements DeviceRepository {

    private final JdbcTemplate jdbc;

    public JdbcDeviceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("ALTER TABLE device ADD COLUMN IF NOT EXISTS manufacturer TEXT");
        jdbc.execute("ALTER TABLE device ADD COLUMN IF NOT EXISTS model TEXT");
        jdbc.execute("ALTER TABLE device ADD COLUMN IF NOT EXISTS area TEXT");
        jdbc.execute("ALTER TABLE device ADD COLUMN IF NOT EXISTS paired_at TIMESTAMPTZ");
        // D4 generic provenance mixin (issue #33) -- created_at/updated_at
        // already exist on this table (JdbcDeviceLifecycleStore); only the
        // actor-attribution columns are new.
        jdbc.execute("ALTER TABLE device ADD COLUMN IF NOT EXISTS created_by TEXT NOT NULL DEFAULT 'system'");
        jdbc.execute("ALTER TABLE device ADD COLUMN IF NOT EXISTS modified_by TEXT NOT NULL DEFAULT 'system'");
        jdbc.execute("ALTER TABLE device ADD COLUMN IF NOT EXISTS version INTEGER NOT NULL DEFAULT 1");
    }

    @Override
    public void upsert(String deviceId, DeviceMetadata metadata) {
        // No row to attach metadata to yet (e.g. an undecided candidate that
        // has never been confirmed -- see registerCandidate()'s own comment
        // on why passive discovery doesn't persist). Nothing to do; this is
        // a normal state, not an error.
        jdbc.update("""
            UPDATE device SET
              manufacturer = COALESCE(?, manufacturer),
              model        = COALESCE(?, model),
              area         = COALESCE(?, area),
              paired_at    = COALESCE(paired_at, ?),
              modified_by  = ?,
              updated_at   = now(),
              version      = version + 1
            WHERE device_id = ?
            """,
            blankToNull(metadata.manufacturer()), blankToNull(metadata.model()), blankToNull(metadata.area()),
            metadata.pairedAt() == null ? null : metadata.pairedAt().atOffset(ZoneOffset.UTC),
            metadata.modifiedBy() == null || metadata.modifiedBy().isBlank() ? "system" : metadata.modifiedBy(),
            deviceId);
    }

    @Override
    public Optional<DeviceMetadata> find(String deviceId) {
        List<DeviceMetadata> rows = jdbc.query("""
            SELECT manufacturer, model, area, paired_at, created_by, created_at,
                   modified_by, updated_at, version
            FROM device WHERE device_id = ?
            """, (rs, rowNum) -> fromRow(rs), deviceId);
        return rows.stream().findFirst();
    }

    @Override
    public Map<String, DeviceMetadata> loadAll() {
        Map<String, DeviceMetadata> result = new LinkedHashMap<>();
        jdbc.query("""
            SELECT device_id, manufacturer, model, area, paired_at, created_by, created_at,
                   modified_by, updated_at, version
            FROM device
            """, rs -> {
                result.put(rs.getString("device_id"), fromRow(rs));
            });
        return result;
    }

    private static DeviceMetadata fromRow(ResultSet rs) throws SQLException {
        return new DeviceMetadata(
            rs.getString("manufacturer"),
            rs.getString("model"),
            rs.getString("area"),
            toInstant(rs, "paired_at"),
            rs.getString("created_by"),
            toInstant(rs, "created_at"),
            rs.getString("modified_by"),
            toInstant(rs, "updated_at"),
            rs.getInt("version"));
    }

    // getTimestamp() on a TIMESTAMPTZ column re-interprets the value through
    // the JVM's default timezone instead of returning the point in time
    // as-is -- getObject(..., OffsetDateTime.class) is the driver-correct
    // way to round-trip an Instant through this column type unambiguously.
    private static Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime odt = rs.getObject(column, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
