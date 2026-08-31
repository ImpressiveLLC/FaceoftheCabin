package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.KnowledgeChunkType;
import com.cabin.orchestrator.devices.model.KnowledgeNode;
import com.cabin.orchestrator.devices.model.KnowledgeSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class JdbcKnowledgeNodeRepository implements KnowledgeNodeRepository {

    private final JdbcTemplate jdbc;

    public JdbcKnowledgeNodeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS knowledge_node (
              entity_ref   TEXT NOT NULL,
              chunk_type   TEXT NOT NULL,
              content      TEXT NOT NULL,
              source       TEXT NOT NULL,
              generated_at TIMESTAMPTZ NOT NULL,
              PRIMARY KEY (entity_ref, chunk_type)
            )""");
    }

    @Override
    public void upsert(KnowledgeNode node) {
        jdbc.update("""
            INSERT INTO knowledge_node (entity_ref, chunk_type, content, source, generated_at)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (entity_ref, chunk_type) DO UPDATE SET
              content      = EXCLUDED.content,
              source       = EXCLUDED.source,
              generated_at = EXCLUDED.generated_at
            """,
            node.entityRef(), node.chunkType().dbValue(), node.content(), node.source().dbValue(),
            node.generatedAt().atOffset(ZoneOffset.UTC));
    }

    @Override
    public List<KnowledgeNode> findByEntityRef(String entityRef) {
        return jdbc.query("""
            SELECT entity_ref, chunk_type, content, source, generated_at
            FROM knowledge_node WHERE entity_ref = ? ORDER BY chunk_type
            """, (rs, rowNum) -> fromRow(rs), entityRef);
    }

    @Override
    public List<KnowledgeNode> loadAll() {
        return jdbc.query("""
            SELECT entity_ref, chunk_type, content, source, generated_at
            FROM knowledge_node ORDER BY entity_ref, chunk_type
            """, (rs, rowNum) -> fromRow(rs));
    }

    private static KnowledgeNode fromRow(ResultSet rs) throws SQLException {
        OffsetDateTime generatedAt = rs.getObject("generated_at", OffsetDateTime.class);
        return new KnowledgeNode(
            rs.getString("entity_ref"),
            KnowledgeChunkType.fromDbValue(rs.getString("chunk_type")),
            rs.getString("content"),
            KnowledgeSource.fromDbValue(rs.getString("source")),
            generatedAt == null ? null : generatedAt.toInstant());
    }
}
