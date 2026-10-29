package com.cabin.orchestrator.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        // Usage summary: added in place so an existing table gains the columns on the next boot.
        jdbc.execute("ALTER TABLE cabin_access_tokens ADD COLUMN IF NOT EXISTS first_used_at TIMESTAMPTZ");
        jdbc.execute("ALTER TABLE cabin_access_tokens ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMPTZ");
        jdbc.execute("ALTER TABLE cabin_access_tokens ADD COLUMN IF NOT EXISTS use_count BIGINT NOT NULL DEFAULT 0");
        // token_id is the link's id, never the bearer token string.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cabin_access_events (
              id           BIGSERIAL PRIMARY KEY,
              token_id     TEXT NOT NULL,
              occurred_at  TIMESTAMPTZ NOT NULL,
              bucket_start TIMESTAMPTZ NOT NULL,
              tier         TEXT NOT NULL,
              route        TEXT NOT NULL,
              policy_class TEXT,
              status       INT NOT NULL,
              client_ip    TEXT,
              user_agent   TEXT,
              hits         INT NOT NULL DEFAULT 1
            )""");
        jdbc.execute("""
            CREATE UNIQUE INDEX IF NOT EXISTS cabin_access_events_bucket
              ON cabin_access_events (token_id, tier, route, status, bucket_start)""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS cabin_access_events_token_time ON cabin_access_events (token_id, occurred_at DESC)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS cabin_access_events_time ON cabin_access_events (occurred_at)");
    }

    /** Requests are collapsed into one row per (link, tier, route, status) per 5 minutes. */
    static final long BUCKET_SECONDS = 300;
    /** A Tier 1 request this long after the link was last seen counts as a new visit. */
    static final long VISIT_GAP_SECONDS = 1800;
    private static final int MAX_FIELD = 300;

    @Override
    public void recordUse(String tokenId, Instant at, String tier, String route, String policyClass,
                          int status, String clientIp, String userAgent, boolean pageOpen) {
        Instant bucket = Instant.ofEpochSecond(Math.floorDiv(at.getEpochSecond(), BUCKET_SECONDS) * BUCKET_SECONDS);
        jdbc.update("""
            INSERT INTO cabin_access_events
              (token_id, occurred_at, bucket_start, tier, route, policy_class, status, client_ip, user_agent, hits)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (token_id, tier, route, status, bucket_start) DO UPDATE SET
              hits = cabin_access_events.hits + 1,
              occurred_at = EXCLUDED.occurred_at,
              client_ip = EXCLUDED.client_ip,
              user_agent = EXCLUDED.user_agent
            """,
            tokenId, Timestamp.from(at), Timestamp.from(bucket), tier, clip(route), policyClass, status,
            clip(clientIp), clip(userAgent));
        // A visit is a demo page load, or any other request after a quiet gap (the UI polls, so
        // counting requests would mean nothing).
        jdbc.update("""
            UPDATE cabin_access_tokens SET
              use_count = use_count + CASE
                WHEN ? THEN 1
                WHEN last_used_at IS NULL OR last_used_at < ? THEN 1
                ELSE 0 END,
              first_used_at = COALESCE(first_used_at, ?),
              last_used_at = ?
            WHERE id = ?
            """,
            pageOpen, Timestamp.from(at.minusSeconds(VISIT_GAP_SECONDS)), Timestamp.from(at), Timestamp.from(at), tokenId);
    }

    @Override
    public List<AccessEvent> events(String tokenId, Instant since, int limit) {
        return jdbc.query("""
            SELECT id, token_id, occurred_at, tier, route, policy_class, status, client_ip, user_agent, hits
            FROM cabin_access_events
            WHERE token_id = ? AND occurred_at >= ?
            ORDER BY occurred_at DESC, id DESC
            LIMIT ?
            """, (rs, i) -> new AccessEvent(
                rs.getLong("id"), rs.getString("token_id"), ts(rs, "occurred_at"), rs.getString("tier"),
                rs.getString("route"), rs.getString("policy_class"), rs.getInt("status"),
                rs.getString("client_ip"), rs.getString("user_agent"), rs.getInt("hits")),
            tokenId, Timestamp.from(since), limit);
    }

    @Override
    public Map<String, AccessUsage> usage() {
        Map<String, AccessUsage> out = new HashMap<>();
        jdbc.query("SELECT id, first_used_at, last_used_at, use_count FROM cabin_access_tokens WHERE last_used_at IS NOT NULL",
            (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.put(rs.getString("id"),
                new AccessUsage(ts(rs, "first_used_at"), ts(rs, "last_used_at"), rs.getLong("use_count"))));
        return out;
    }

    @Override
    public int purgeEventsBefore(Instant cutoff) {
        return jdbc.update("DELETE FROM cabin_access_events WHERE occurred_at < ?", Timestamp.from(cutoff));
    }

    private static String clip(String value) {
        return value == null || value.length() <= MAX_FIELD ? value : value.substring(0, MAX_FIELD);
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
