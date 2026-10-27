package com.cabin.orchestrator.roofspec;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
public class JdbcRoofSpecStore implements RoofSpecStore {

    private final JdbcTemplate jdbc;

    public JdbcRoofSpecStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS roof_spec_entries (
              id             TEXT PRIMARY KEY,
              kind           TEXT NOT NULL,
              item_key       TEXT,
              body           TEXT NOT NULL,
              author         TEXT NOT NULL,
              author_kind    TEXT NOT NULL,
              guest_token_id TEXT,
              created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
              deleted_at     TIMESTAMPTZ
            )""");
    }

    @Override
    public List<RoofSpecEntry> loadLive() {
        return jdbc.query("SELECT * FROM roof_spec_entries WHERE deleted_at IS NULL ORDER BY created_at ASC", this::map);
    }

    @Override
    public long countLiveByGuestToken(String guestTokenId) {
        Long n = jdbc.queryForObject(
            "SELECT count(*) FROM roof_spec_entries WHERE guest_token_id = ? AND deleted_at IS NULL", Long.class, guestTokenId);
        return n == null ? 0 : n;
    }

    @Override
    public void save(RoofSpecEntry e) {
        jdbc.update("""
            INSERT INTO roof_spec_entries (id, kind, item_key, body, author, author_kind, guest_token_id, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            e.id(), e.kind(), e.itemKey(), e.body(), e.author(), e.authorKind(), e.guestTokenId(), Timestamp.from(e.createdAt()));
    }

    @Override
    public void softDelete(String id, Instant at) {
        jdbc.update("UPDATE roof_spec_entries SET deleted_at = ? WHERE id = ?", Timestamp.from(at), id);
    }

    private RoofSpecEntry map(ResultSet rs, int i) throws SQLException {
        Timestamp del = rs.getTimestamp("deleted_at");
        return new RoofSpecEntry(rs.getString("id"), rs.getString("kind"), rs.getString("item_key"),
            rs.getString("body"), rs.getString("author"), rs.getString("author_kind"), rs.getString("guest_token_id"),
            rs.getTimestamp("created_at").toInstant(), del == null ? null : del.toInstant());
    }
}
