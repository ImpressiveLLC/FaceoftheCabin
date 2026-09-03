package com.cabin.orchestrator.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcManagedUserSessionStore implements ManagedUserSessionStore {

    private final JdbcTemplate jdbc;

    public JdbcManagedUserSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS managed_user_sessions (
              token           TEXT PRIMARY KEY,
              managed_user_id TEXT NOT NULL,
              expires_at      TIMESTAMPTZ NOT NULL,
              revoked_at      TIMESTAMPTZ,
              created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
            )""");
    }

    @Override
    public void save(ManagedUserSession session) {
        jdbc.update("""
            INSERT INTO managed_user_sessions (token, managed_user_id, expires_at, created_at)
            VALUES (?, ?, ?, ?)
            """,
            session.token(), session.managedUserId(), Timestamp.from(session.expiresAt()), Timestamp.from(session.createdAt()));
    }

    @Override
    public Optional<ManagedUserSession> findByToken(String token) {
        return jdbc.query("SELECT * FROM managed_user_sessions WHERE token = ?", this::map, token)
            .stream().findFirst();
    }

    @Override
    public void revoke(String token, Instant revokedAt) {
        jdbc.update("UPDATE managed_user_sessions SET revoked_at = ? WHERE token = ?", Timestamp.from(revokedAt), token);
    }

    private ManagedUserSession map(ResultSet rs, int i) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new ManagedUserSession(
            rs.getString("token"), rs.getString("managed_user_id"),
            rs.getTimestamp("expires_at").toInstant(),
            revoked == null ? null : revoked.toInstant(),
            rs.getTimestamp("created_at").toInstant());
    }
}
