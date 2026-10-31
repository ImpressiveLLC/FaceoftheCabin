package com.cabin.orchestrator.presence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin-managed and durable, unlike PresenceSignalRegistry's transient signals: an assignment is made once by an explicit
 * action and must survive restarts exactly as made. Created in place on first boot, like the other admin-managed stores.
 */
@Repository
public class JdbcTrackedPersonStore implements TrackedPersonStore {

    private final JdbcTemplate jdbc;

    public JdbcTrackedPersonStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tracked_person_location_role (
              person_id         TEXT NOT NULL,
              location          TEXT NOT NULL,
              role              TEXT NOT NULL,
              family_profile_id TEXT,
              created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
              PRIMARY KEY (person_id, location, role)
            )""");
    }

    @Override
    public List<Assignment> findAll() {
        Map<String, Assignment> byKey = new LinkedHashMap<>();
        jdbc.query("SELECT person_id, location, role, family_profile_id FROM tracked_person_location_role ORDER BY location, person_id", rs -> {
            String person = rs.getString("person_id");
            String location = rs.getString("location");
            Assignment current = byKey.get(person + "\u0000" + location);
            Set<LocationRole> roles = current == null ? EnumSet.noneOf(LocationRole.class) : EnumSet.copyOf(current.roles());
            roles.add(LocationRole.valueOf(rs.getString("role")));
            byKey.put(person + "\u0000" + location, new Assignment(person, location, roles, rs.getString("family_profile_id")));
        });
        return new ArrayList<>(byKey.values());
    }

    @Override
    public Set<LocationRole> rolesOf(String personId, String location) {
        Set<LocationRole> roles = EnumSet.noneOf(LocationRole.class);
        jdbc.query("SELECT role FROM tracked_person_location_role WHERE person_id = ? AND location = ?",
            rs -> { roles.add(LocationRole.valueOf(rs.getString("role"))); }, personId, location);
        return roles;
    }

    @Override
    @Transactional
    public void setRoles(String personId, String location, Set<LocationRole> roles) {
        jdbc.update("DELETE FROM tracked_person_location_role WHERE person_id = ? AND location = ?", personId, location);
        for (LocationRole role : roles) {
            jdbc.update("INSERT INTO tracked_person_location_role (person_id, location, role) VALUES (?, ?, ?)",
                personId, location, role.name());
        }
    }
}
