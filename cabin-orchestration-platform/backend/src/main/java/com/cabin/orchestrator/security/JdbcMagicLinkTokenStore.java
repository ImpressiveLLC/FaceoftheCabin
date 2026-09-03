package com.cabin.orchestrator.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcMagicLinkTokenStore implements MagicLinkTokenStore {

    private final JdbcTemplate jdbc;

    public JdbcMagicLinkTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS managed_user_magic_links (
              token           TEXT PRIMARY KEY,
              managed_user_id TEXT NOT NULL,
              expires_at      TIMESTAMPTZ NOT NULL,
              consumed_at     TIMESTAMPTZ,
              created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
            )""");
    }

    @Override
    public void save(MagicLinkToken token) {
        jdbc.update("""
            INSERT INTO managed_user_magic_links (token, managed_user_id, expires_at, created_at)
            VALUES (?, ?, ?, ?)
            """,
            token.token(), token.managedUserId(), Timestamp.from(token.expiresAt()), Timestamp.from(token.createdAt()));
    }

    @Override
    public Optional<MagicLinkToken> findByToken(String token) {
        return jdbc.query("SELECT * FROM managed_user_magic_links WHERE token = ?", this::map, token)
            .stream().findFirst();
    }

    @Override
    public void markConsumed(String token, Instant consumedAt) {
        jdbc.update("UPDATE managed_user_magic_links SET consumed_at = ? WHERE token = ?",
            Timestamp.from(consumedAt), token);
    }

    private MagicLinkToken map(ResultSet rs, int i) throws SQLException {
        Timestamp consumed = rs.getTimestamp("consumed_at");
        return new MagicLinkToken(
            rs.getString("token"), rs.getString("managed_user_id"),
            rs.getTimestamp("expires_at").toInstant(),
            consumed == null ? null : consumed.toInstant(),
            rs.getTimestamp("created_at").toInstant());
    }
}
