package com.cabin.orchestrator.opportunities;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Same idempotent CREATE TABLE IF NOT EXISTS + upsert pattern as
 * JdbcWorkflowRuleStore -- real, admin-mutable rows (acknowledge/resolve),
 * not a reseeded-from-code vocabulary table.
 */
@Repository
public class JdbcOptimizationOpportunityStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcOptimizationOpportunityStore.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcOptimizationOpportunityStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS optimization_opportunity (
              id                TEXT PRIMARY KEY,
              opportunity_type  TEXT NOT NULL,
              device_id         TEXT NOT NULL,
              detected_at       TIMESTAMPTZ NOT NULL,
              evidence          JSONB NOT NULL DEFAULT '{}',
              status            TEXT NOT NULL DEFAULT 'OPEN',
              resolved_at       TIMESTAMPTZ
            )""");
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS optimization_opportunity_device_type_idx
            ON optimization_opportunity (device_id, opportunity_type)""");
    }

    public void save(OptimizationOpportunity opportunity) {
        jdbc.update("""
            INSERT INTO optimization_opportunity (id, opportunity_type, device_id, detected_at, evidence, status, resolved_at)
            VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              opportunity_type = EXCLUDED.opportunity_type,
              device_id = EXCLUDED.device_id,
              detected_at = EXCLUDED.detected_at,
              evidence = EXCLUDED.evidence,
              status = EXCLUDED.status,
              resolved_at = EXCLUDED.resolved_at
            """,
            opportunity.id(), opportunity.opportunityType().name(), opportunity.deviceId(),
            Timestamp.from(opportunity.detectedAt()), toJson(opportunity.evidence()), opportunity.status().name(),
            opportunity.resolvedAt() == null ? null : Timestamp.from(opportunity.resolvedAt()));
    }

    /** Newest first. statusFilter is nullable -- null returns every status. */
    public List<OptimizationOpportunity> findAll(OpportunityStatus statusFilter) {
        List<OptimizationOpportunity> rows = new ArrayList<>();
        String sql = statusFilter == null
            ? "SELECT * FROM optimization_opportunity"
            : "SELECT * FROM optimization_opportunity WHERE status = ?";
        Object[] args = statusFilter == null ? new Object[0] : new Object[]{statusFilter.name()};
        jdbc.query(sql, rs -> {
            String id = rs.getString("id");
            try {
                rows.add(fromRow(rs));
            } catch (Exception e) {
                log.warn("Skipping invalid optimization_opportunity row {}: {}", id, e.getMessage());
            }
        }, args);
        rows.sort(Comparator.comparing(OptimizationOpportunity::detectedAt).reversed());
        return rows;
    }

    public Optional<OptimizationOpportunity> findById(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM optimization_opportunity WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromRow(rows.get(0)));
    }

    /** The job's dedup check: an existing not-yet-resolved row for this device+type, if any. */
    public Optional<OptimizationOpportunity> findOpenOrAcknowledged(String deviceId, OpportunityType type) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT * FROM optimization_opportunity
            WHERE device_id = ? AND opportunity_type = ? AND status IN ('OPEN', 'ACKNOWLEDGED')
            ORDER BY detected_at DESC LIMIT 1
            """, deviceId, type.name());
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromRow(rows.get(0)));
    }

    private OptimizationOpportunity fromRow(Map<String, Object> row) {
        Object resolvedAtVal = row.get("resolved_at");
        return new OptimizationOpportunity(
            (String) row.get("id"),
            OpportunityType.valueOf((String) row.get("opportunity_type")),
            (String) row.get("device_id"),
            toInstant(row.get("detected_at")),
            parseJson(row.get("evidence")),
            OpportunityStatus.valueOf((String) row.get("status")),
            resolvedAtVal == null ? null : toInstant(resolvedAtVal));
    }

    private OptimizationOpportunity fromRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        java.sql.Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new OptimizationOpportunity(
            rs.getString("id"),
            OpportunityType.valueOf(rs.getString("opportunity_type")),
            rs.getString("device_id"),
            rs.getTimestamp("detected_at").toInstant(),
            parseJson(rs.getObject("evidence")),
            OpportunityStatus.valueOf(rs.getString("status")),
            resolvedAt == null ? null : resolvedAt.toInstant());
    }

    private Instant toInstant(Object value) {
        return value instanceof Timestamp t ? t.toInstant() : Instant.now();
    }

    // The jsonb column comes back from the JDBC driver as a PGobject, not a
    // String -- same handling as CabinEventService.parseJson().
    private Map<String, Object> parseJson(Object payload) {
        if (payload == null) return Map.of();
        String json = payload.toString();
        if (json.isBlank()) return Map.of();
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private String toJson(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) return "{}";
        try { return mapper.writeValueAsString(evidence); }
        catch (Exception e) { return "{}"; }
    }
}
