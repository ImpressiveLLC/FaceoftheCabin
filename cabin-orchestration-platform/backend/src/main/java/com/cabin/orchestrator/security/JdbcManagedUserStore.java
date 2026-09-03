package com.cabin.orchestrator.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

/** Genuinely mutable, admin-managed data -- mirrors JdbcCabinAccessTokenStore's own doc/pattern. */
@Repository
public class JdbcManagedUserStore implements ManagedUserStore {

    private final JdbcTemplate jdbc;

    public JdbcManagedUserStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS managed_users (
              id          TEXT PRIMARY KEY,
              email       TEXT NOT NULL UNIQUE,
              name        TEXT NOT NULL,
              role        TEXT NOT NULL,
              active      BOOLEAN NOT NULL DEFAULT true,
              created_by  TEXT,
              created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
            )""");
    }

    @Override
    public List<ManagedUser> loadAll() {
        return jdbc.query("SELECT * FROM managed_users ORDER BY created_at DESC", this::map);
    }

    @Override
    public Optional<ManagedUser> findById(String id) {
        return jdbc.query("SELECT * FROM managed_users WHERE id = ?", this::map, id).stream().findFirst();
    }

    @Override
    public Optional<ManagedUser> findByEmail(String email) {
        return jdbc.query("SELECT * FROM managed_users WHERE lower(email) = lower(?)", this::map, email)
            .stream().findFirst();
    }

    @Override
    public void save(ManagedUser user) {
        jdbc.update("""
            INSERT INTO managed_users (id, email, name, role, active, created_by, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
              email = EXCLUDED.email, name = EXCLUDED.name, role = EXCLUDED.role, active = EXCLUDED.active
            """,
            user.id(), user.email(), user.name(), user.role().name(), user.active(),
            user.createdBy(), Timestamp.from(user.createdAt()));
    }

    private ManagedUser map(ResultSet rs, int i) throws SQLException {
        return new ManagedUser(
            rs.getString("id"), rs.getString("email"), rs.getString("name"),
            ManagedUserRole.valueOf(rs.getString("role")), rs.getBoolean("active"),
            rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());
    }
}
