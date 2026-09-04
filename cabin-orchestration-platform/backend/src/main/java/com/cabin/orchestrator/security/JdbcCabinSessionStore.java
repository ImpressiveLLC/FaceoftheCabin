package com.cabin.orchestrator.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class JdbcCabinSessionStore implements CabinSessionStore {

    private final JdbcTemplate jdbc;

    public JdbcCabinSessionStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cabin_sessions (
              token         TEXT PRIMARY KEY,
              google_email  TEXT NOT NULL,
              expires_at    TIMESTAMPTZ NOT NULL,
              revoked_at    TIMESTAMPTZ,
              created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
            )""");
    }

    @Override
    public void save(CabinSession session) {
        jdbc.update("""
            INSERT INTO cabin_sessions (token, google_email, expires_at, created_at)
            VALUES (?, ?, ?, ?)
            """,
            session.token(), session.googleEmail(), Timestamp.from(session.expiresAt()), Timestamp.from(session.createdAt()));
    }

    @Override
    public Optional<CabinSession> findByToken(String token) {
        return jdbc.query("SELECT * FROM cabin_sessions WHERE token = ?", this::map, token)
            .stream().findFirst();
    }

    @Override
    public void extend(String token, Instant newExpiresAt) {
        jdbc.update("UPDATE cabin_sessions SET expires_at = ? WHERE token = ?", Timestamp.from(newExpiresAt), token);
    }

    @Override
    public void revoke(String token, Instant revokedAt) {
        jdbc.update("UPDATE cabin_sessions SET revoked_at = ? WHERE token = ?", Timestamp.from(revokedAt), token);
    }

    private CabinSession map(ResultSet rs, int i) throws SQLException {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        return new CabinSession(
            rs.getString("token"), rs.getString("google_email"),
            rs.getTimestamp("expires_at").toInstant(),
            revoked == null ? null : revoked.toInstant(),
            rs.getTimestamp("created_at").toInstant());
    }
}
