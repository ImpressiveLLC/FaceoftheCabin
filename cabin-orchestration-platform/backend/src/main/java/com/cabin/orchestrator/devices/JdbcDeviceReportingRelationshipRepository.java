package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * New table, separate from `device` -- a device can report zero, one, or
 * many measurement types, so this is naturally its own row-per-field table
 * rather than more columns bolted onto `device` (see JdbcDeviceRepository).
 */
@Repository
public class JdbcDeviceReportingRelationshipRepository implements DeviceReportingRelationshipRepository {

    private final JdbcTemplate jdbc;

    public JdbcDeviceReportingRelationshipRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_reporting_relationship (
              device_id           TEXT NOT NULL,
              semantic_field      TEXT NOT NULL,
              measurement_type    TEXT NOT NULL,
              confirmation_source TEXT NOT NULL,
              confirmed_at        TIMESTAMPTZ NOT NULL,
              display_label       TEXT,
              PRIMARY KEY (device_id, semantic_field)
            )""");
        // D13 (2026-09-03): this table already existed live before displayLabel
        // did -- CREATE TABLE IF NOT EXISTS above is a no-op against it, so the
        // column needs adding explicitly, same pattern JdbcDeviceRepository/
        // JdbcWorkflowVocabularyStore/JdbcWorkflowRuleStore already use for this.
        jdbc.execute("ALTER TABLE device_reporting_relationship ADD COLUMN IF NOT EXISTS display_label TEXT");
    }

    // The CASE below duplicates ConfirmationSource.priority() in SQL so the
    // ON CONFLICT clause can compare ranks without a round-trip -- if that
    // enum's priorities ever change, this must change with it.
    private static final String SOURCE_RANK_SQL = """
        CASE %s
          WHEN 'manual_override' THEN 4
          WHEN 'vendor_spec' THEN 3
          WHEN 'empirical_observation' THEN 2
          ELSE 1
        END""";

    @Override
    public void upsert(DeviceReportingRelationship relationship) {
        jdbc.update("""
            INSERT INTO device_reporting_relationship
              (device_id, semantic_field, measurement_type, confirmation_source, confirmed_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (device_id, semantic_field) DO UPDATE SET
              measurement_type    = EXCLUDED.measurement_type,
              confirmation_source = EXCLUDED.confirmation_source,
              confirmed_at        = EXCLUDED.confirmed_at
            WHERE %s >= %s
            """.formatted(
                SOURCE_RANK_SQL.formatted("EXCLUDED.confirmation_source"),
                SOURCE_RANK_SQL.formatted("device_reporting_relationship.confirmation_source")),
            relationship.deviceId(), relationship.semanticField(), relationship.measurementType(),
            relationship.confirmationSource().dbValue(),
            relationship.confirmedAt().atOffset(ZoneOffset.UTC));
    }

    @Override
    public List<DeviceReportingRelationship> findByDevice(String deviceId) {
        return jdbc.query("""
            SELECT device_id, semantic_field, measurement_type, confirmation_source, confirmed_at, display_label
            FROM device_reporting_relationship WHERE device_id = ? ORDER BY semantic_field
            """, (rs, rowNum) -> fromRow(rs), deviceId);
    }

    @Override
    public Map<String, List<DeviceReportingRelationship>> loadAll() {
        List<DeviceReportingRelationship> all = jdbc.query("""
            SELECT device_id, semantic_field, measurement_type, confirmation_source, confirmed_at, display_label
            FROM device_reporting_relationship ORDER BY device_id, semantic_field
            """, (rs, rowNum) -> fromRow(rs));
        Map<String, List<DeviceReportingRelationship>> byDevice = new LinkedHashMap<>();
        for (DeviceReportingRelationship r : all) {
            byDevice.computeIfAbsent(r.deviceId(), k -> new java.util.ArrayList<>()).add(r);
        }
        return byDevice;
    }

    /**
     * D13: the one explicit curation action. A blank displayLabel clears it
     * back to null (matching this codebase's existing "empty value is an
     * explicit removal" convention -- see DeviceRegistry.registerCandidate()'s
     * own comment on the same convention for expectedCheckinMinutes). Never
     * touches any other column, and never creates a row that doesn't already
     * exist -- a display_label can only ever attach to a measurement this
     * device has actually confirmed reporting.
     */
    @Override
    public void setDisplayLabel(String deviceId, String semanticField, String displayLabel) {
        String normalized = (displayLabel == null || displayLabel.isBlank()) ? null : displayLabel.trim();
        jdbc.update("""
            UPDATE device_reporting_relationship SET display_label = ?
            WHERE device_id = ? AND semantic_field = ?
            """, normalized, deviceId, semanticField);
    }

    private static DeviceReportingRelationship fromRow(ResultSet rs) throws SQLException {
        OffsetDateTime confirmedAt = rs.getObject("confirmed_at", OffsetDateTime.class);
        return new DeviceReportingRelationship(
            rs.getString("device_id"),
            rs.getString("semantic_field"),
            rs.getString("measurement_type"),
            ConfirmationSource.fromDbValue(rs.getString("confirmation_source")),
            confirmedAt == null ? null : confirmedAt.toInstant(),
            rs.getString("display_label"));
    }
}
