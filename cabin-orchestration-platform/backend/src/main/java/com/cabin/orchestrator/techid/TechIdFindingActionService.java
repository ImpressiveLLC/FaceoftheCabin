package com.cabin.orchestrator.techid;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** JdbcTemplate + self-migrating-table pattern, matching TechIdFindingService. */
@Service
public class TechIdFindingActionService {

    private final JdbcTemplate jdbc;

    public TechIdFindingActionService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tech_id_finding_action (
              id          VARCHAR(64) PRIMARY KEY,
              finding_id  VARCHAR(64) NOT NULL,
              action_type VARCHAR(32) NOT NULL,
              actor_email VARCHAR(256) NOT NULL,
              detail      TEXT,
              created_at  BIGINT NOT NULL
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_tech_id_finding_action_finding ON tech_id_finding_action (finding_id, created_at DESC)");
    }

    public TechIdFindingAction record(String findingId, String actionType, String actorEmail, String detail) {
        TechIdFindingAction action = new TechIdFindingAction(
            UUID.randomUUID().toString(), findingId, actionType, actorEmail, detail, System.currentTimeMillis());
        jdbc.update("""
            INSERT INTO tech_id_finding_action (id, finding_id, action_type, actor_email, detail, created_at)
            VALUES (?,?,?,?,?,?)
            """,
            action.id(), action.findingId(), action.actionType(), action.actorEmail(), action.detail(), action.createdAt());
        return action;
    }

    public List<TechIdFindingAction> forFinding(String findingId) {
        return jdbc.queryForList(
                "SELECT * FROM tech_id_finding_action WHERE finding_id = ? ORDER BY created_at DESC", findingId)
            .stream().map(this::fromRow).toList();
    }

    private TechIdFindingAction fromRow(Map<String, Object> row) {
        return new TechIdFindingAction(
            (String) row.get("id"),
            (String) row.get("finding_id"),
            (String) row.get("action_type"),
            (String) row.get("actor_email"),
            (String) row.get("detail"),
            ((Number) row.get("created_at")).longValue());
    }
}
