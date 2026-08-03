package com.cabin.orchestrator.techid;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Same JdbcTemplate + self-migrating-table pattern as CabinEventService/
 * FamilyProfileService -- no migration framework in this project yet.
 *
 * This is the durable, live half of the Tech ID Service: findings land
 * here immediately (no PR-merge wait), independent of whichever provider
 * did the research. Reconciling a finding into docs/ontology.yaml's
 * discovery: fields (the versioned, git-tracked source of truth) is a
 * separate, deliberate step -- either the same provider opens a PR, or a
 * human does it after reviewing the live feed here. Two speeds on
 * purpose: fast/live for "keep things fresh," slow/versioned for the
 * ontology's own audit trail.
 */
@Service
public class TechIdFindingService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public TechIdFindingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tech_id_finding (
              id           VARCHAR(64) PRIMARY KEY,
              entity_id    VARCHAR(128) NOT NULL,
              provider     VARCHAR(128) NOT NULL,
              finding_type VARCHAR(64) NOT NULL,
              summary      TEXT NOT NULL,
              confidence   VARCHAR(16) NOT NULL,
              sources      TEXT NOT NULL,
              status       VARCHAR(32) NOT NULL DEFAULT 'new',
              checked_at   BIGINT NOT NULL,
              created_at   BIGINT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tech_id_finding_entity ON tech_id_finding (entity_id, created_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tech_id_finding_status ON tech_id_finding (status)");
    }

    public TechIdFinding submit(String entityId, String provider, String findingType, String summary,
                                 String confidence, List<String> sources, long checkedAt) {
        try {
            TechIdFinding finding = new TechIdFinding(
                UUID.randomUUID().toString(), entityId, provider, findingType, summary,
                confidence, sources, "new", checkedAt, System.currentTimeMillis());
            jdbc.update("""
                INSERT INTO tech_id_finding
                  (id, entity_id, provider, finding_type, summary, confidence, sources, status, checked_at, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                finding.id(), finding.entityId(), finding.provider(), finding.findingType(), finding.summary(),
                finding.confidence(), mapper.writeValueAsString(finding.sources()), finding.status(),
                finding.checkedAt(), finding.createdAt());
            return finding;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("Could not serialize sources", e);
        }
    }

    public List<TechIdFinding> recent(String entityId, int limit) {
        String sql = entityId != null
            ? "SELECT * FROM tech_id_finding WHERE entity_id = ? ORDER BY created_at DESC LIMIT ?"
            : "SELECT * FROM tech_id_finding ORDER BY created_at DESC LIMIT ?";
        List<Map<String, Object>> rows = entityId != null
            ? jdbc.queryForList(sql, entityId, limit)
            : jdbc.queryForList(sql, limit);
        return rows.stream().map(this::fromRow).toList();
    }

    public TechIdFinding updateStatus(String id, String status) {
        int rows = jdbc.update("UPDATE tech_id_finding SET status = ? WHERE id = ?", status, id);
        if (rows == 0) return null;
        return jdbc.queryForList("SELECT * FROM tech_id_finding WHERE id = ?", id)
            .stream().map(this::fromRow).findFirst().orElse(null);
    }

    @SuppressWarnings("unchecked")
    private TechIdFinding fromRow(Map<String, Object> row) {
        List<String> sources;
        try {
            sources = mapper.readValue((String) row.get("sources"), List.class);
        } catch (Exception e) {
            sources = List.of();
        }
        return new TechIdFinding(
            (String) row.get("id"),
            (String) row.get("entity_id"),
            (String) row.get("provider"),
            (String) row.get("finding_type"),
            (String) row.get("summary"),
            (String) row.get("confidence"),
            sources,
            (String) row.get("status"),
            ((Number) row.get("checked_at")).longValue(),
            ((Number) row.get("created_at")).longValue());
    }
}
