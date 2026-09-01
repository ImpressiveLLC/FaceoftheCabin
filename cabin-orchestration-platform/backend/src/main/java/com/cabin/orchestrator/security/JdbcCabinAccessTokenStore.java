package com.cabin.orchestrator.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Genuinely mutable, admin-managed data -- unlike JdbcWorkflowVocabularyStore's
 * reseed-from-code tables, a share link is created once by an explicit admin
 * action and must survive restarts exactly as created; nothing here ever
 * reseeds or overwrites a row on boot. scope is stored the same
 * string_to_array(...) way JdbcDeviceLifecycleStore stores capabilities --
 * avoids needing a java.sql.Array on the write side for a plain JdbcTemplate.
 */
@Repository
public class JdbcCabinAccessTokenStore implements CabinAccessTokenStore {

    private final JdbcTemplate jdbc;

    public JdbcCabinAccessTokenStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cabin_access_tokens (
              id          TEXT PRIMARY KEY,
              token       TEXT NOT NULL UNIQUE,
              label       TEXT NOT NULL,
              scope       TEXT[] NOT NULL,
              expires_at  TIMESTAMPTZ,
              revoked_at  TIMESTAMPTZ,
              created_by  TEXT,
              created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""");
    }

    @Override
    public List<CabinAccessToken> loadAll() {
        return jdbc.query("SELECT * FROM cabin_access_tokens ORDER BY created_at DESC", this::map);
    }

    @Override
    public Optional<CabinAccessToken> findByToken(String token) {
        return jdbc.query("SELECT * FROM cabin_access_tokens WHERE token = ?", this::map, token)
            .stream().findFirst();
    }

    @Override
    public void save(CabinAccessToken t) {
        jdbc.update("""
            INSERT INTO cabin_access_tokens (id, token, label, scope, expires_at, created_by, created_at)
            VALUES (?, ?, ?, string_to_array(?, ','), ?, ?, ?)
            """,
            t.id(), t.token(), t.label(), String.join(",", t.scope()),
            t.expiresAt() == null ? null : Timestamp.from(t.expiresAt()),
            t.createdBy(), Timestamp.from(t.createdAt()));
    }

    @Override
    public void revoke(String id, Instant revokedAt) {
        jdbc.update("UPDATE cabin_access_tokens SET revoked_at = ? WHERE id = ?", Timestamp.from(revokedAt), id);
    }

    private CabinAccessToken map(ResultSet rs, int i) throws SQLException {
        return new CabinAccessToken(
            rs.getString("id"), rs.getString("token"), rs.getString("label"),
            scopeList(rs.getArray("scope")),
            ts(rs, "expires_at"), ts(rs, "revoked_at"),
            rs.getString("created_by"), ts(rs, "created_at"));
    }

    private static Instant ts(ResultSet rs, String col) throws SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    private static List<String> scopeList(Array sqlArray) throws SQLException {
        if (sqlArray == null) return List.of();
        Object raw = sqlArray.getArray();
        if (!(raw instanceof Object[] values)) return List.of();
        return Arrays.stream(values).map(String::valueOf).toList();
    }
}
