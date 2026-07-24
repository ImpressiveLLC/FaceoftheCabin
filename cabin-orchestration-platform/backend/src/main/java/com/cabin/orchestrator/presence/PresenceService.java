package com.cabin.orchestrator.presence;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

@Service
public class PresenceService {

    private final JdbcTemplate jdbc;
    private final AtomicReference<PresenceProfile> current = new AtomicReference<>(PresenceProfile.AT_HOME);

    public PresenceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS active_presence (
              id INT PRIMARY KEY,
              profile VARCHAR(32) NOT NULL DEFAULT 'AT_HOME',
              updated_at TIMESTAMPTZ DEFAULT now()
            )""");
        jdbc.execute("INSERT INTO active_presence (id, profile) VALUES (1, 'AT_HOME') ON CONFLICT DO NOTHING");
        String saved = jdbc.queryForObject("SELECT profile FROM active_presence WHERE id = 1", String.class);
        if (saved != null) {
            try { current.set(PresenceProfile.valueOf(saved)); }
            catch (IllegalArgumentException ignored) {}
        }
    }

    public PresenceProfile get() { return current.get(); }

    public PresenceProfile set(PresenceProfile profile) {
        jdbc.update("UPDATE active_presence SET profile = ?, updated_at = now() WHERE id = 1", profile.name());
        current.set(profile);
        return profile;
    }
}
