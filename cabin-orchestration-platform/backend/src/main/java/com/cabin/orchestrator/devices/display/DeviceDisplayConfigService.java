package com.cabin.orchestrator.devices.display;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class DeviceDisplayConfigService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeviceDisplayConfigService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @PostConstruct
    void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device_display_config (
              device_id        VARCHAR(128) NOT NULL,
              location         VARCHAR(32)  NOT NULL,
              presence_profile VARCHAR(32)  NOT NULL,
              display_name     VARCHAR(256),
              state_label_map  TEXT,
              severity_override VARCHAR(16),
              PRIMARY KEY (device_id, location, presence_profile)
            )""");
    }

    public Optional<DeviceDisplayConfig> get(String deviceId, String location, String profile) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT * FROM device_display_config
            WHERE device_id = ? AND location = ? AND presence_profile = ?""",
            deviceId, location, profile);
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromRow(rows.get(0)));
    }

    /** All configs for a given presence profile — used for bulk tile rendering. */
    public List<DeviceDisplayConfig> allForProfile(String profile) {
        return jdbc.queryForList(
            "SELECT * FROM device_display_config WHERE presence_profile = ?", profile)
            .stream().map(this::fromRow).toList();
    }

    /** All configs across all profiles for a given device — used by device detail pane. */
    public List<DeviceDisplayConfig> allForDevice(String deviceId) {
        return jdbc.queryForList(
            "SELECT * FROM device_display_config WHERE device_id = ?", deviceId)
            .stream().map(this::fromRow).toList();
    }

    public DeviceDisplayConfig upsert(DeviceDisplayConfig cfg) {
        String labelJson = toJson(cfg.stateLabelMap());
        jdbc.update("""
            INSERT INTO device_display_config
              (device_id, location, presence_profile, display_name, state_label_map, severity_override)
            VALUES (?,?,?,?,?,?)
            ON CONFLICT (device_id, location, presence_profile) DO UPDATE SET
              display_name      = EXCLUDED.display_name,
              state_label_map   = EXCLUDED.state_label_map,
              severity_override = EXCLUDED.severity_override""",
            cfg.deviceId(), cfg.location(), cfg.presenceProfile(),
            cfg.displayName(), labelJson, cfg.severityOverride());
        return cfg;
    }

    public void delete(String deviceId, String location, String profile) {
        jdbc.update("""
            DELETE FROM device_display_config
            WHERE device_id = ? AND location = ? AND presence_profile = ?""",
            deviceId, location, profile);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private DeviceDisplayConfig fromRow(Map<String, Object> row) {
        return new DeviceDisplayConfig(
            (String) row.get("device_id"),
            (String) row.get("location"),
            (String) row.get("presence_profile"),
            (String) row.get("display_name"),
            parseJson((String) row.get("state_label_map")),
            (String) row.get("severity_override"));
    }

    private Map<String, String> parseJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private String toJson(Map<String, String> map) {
        if (map == null || map.isEmpty()) return null;
        try { return mapper.writeValueAsString(map); }
        catch (Exception e) { return null; }
    }
}
