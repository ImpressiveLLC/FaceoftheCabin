package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceLifecycleState;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Uses the registry's existing {@code device} table. Lifecycle-specific values
 * live in its JSONB config column so older installations need no destructive
 * schema migration and unrelated config keys can be preserved.
 */
@Repository
public class JdbcDeviceLifecycleStore implements DeviceLifecycleStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcDeviceLifecycleStore.class);
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JdbcDeviceLifecycleStore(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS device (
              device_id    TEXT PRIMARY KEY,
              name         TEXT NOT NULL,
              type         TEXT NOT NULL,
              capabilities TEXT[],
              protocol     TEXT,
              config       JSONB,
              created_at   TIMESTAMPTZ DEFAULT now(),
              updated_at   TIMESTAMPTZ DEFAULT now()
            )""");
        jdbc.execute("""
            CREATE INDEX IF NOT EXISTS device_lifecycle_state_idx
            ON device ((config->>'lifecycleState'))""");
    }

    @Override
    public Map<String, DeviceLifecycleRecord> loadAll() {
        Map<String, DeviceLifecycleRecord> records = new LinkedHashMap<>();
        jdbc.query("""
            SELECT device_id, name, type, capabilities, protocol, config::text AS config
            FROM device
            WHERE config ? 'lifecycleState'
            """, rs -> {
                String deviceId = rs.getString("device_id");
                try {
                    JsonNode config = mapper.readTree(rs.getString("config"));
                    DeviceDescriptor descriptor = new DeviceDescriptor(
                        deviceId,
                        rs.getString("name"),
                        DeviceType.valueOf(rs.getString("type")),
                        capabilities(rs.getArray("capabilities")),
                        rs.getString("protocol"),
                        text(config, "connectionString", ""),
                        config.path("enabled").asBoolean(false),
                        text(config, "location", "cabin"));
                    DeviceLifecycleState lifecycle = DeviceLifecycleState.valueOf(
                        config.path("lifecycleState").asText());
                    records.put(deviceId, new DeviceLifecycleRecord(
                        descriptor, lifecycle, config.path("configurationAsserted").asBoolean(false)));
                } catch (Exception parsingFailure) {
                    // A malformed or future-version row must not prevent every
                    // other persisted device from being restored.
                    log.warn("Skipping invalid persisted lifecycle for device {}: {}",
                        deviceId, parsingFailure.getMessage());
                }
            });
        return records;
    }

    @Override
    public void save(DeviceLifecycleRecord record) {
        DeviceDescriptor descriptor = record.descriptor();
        String capabilities = descriptor.capabilities().stream()
            .map(Enum::name).sorted().collect(Collectors.joining(","));
        jdbc.update("""
            INSERT INTO device (device_id, name, type, capabilities, protocol, config)
            VALUES (?, ?, ?, string_to_array(NULLIF(?, ''), ','), ?, ?::jsonb)
            ON CONFLICT (device_id) DO UPDATE SET
              name = EXCLUDED.name,
              type = EXCLUDED.type,
              capabilities = EXCLUDED.capabilities,
              protocol = EXCLUDED.protocol,
              config = COALESCE(device.config, '{}'::jsonb) || EXCLUDED.config,
              updated_at = now()
            """,
            descriptor.deviceId(), descriptor.name(), descriptor.type().name(), capabilities,
            descriptor.protocolAdapter(), toJson(record));
    }

    @Override
    public void delete(String deviceId) {
        jdbc.update("DELETE FROM device WHERE device_id = ?", deviceId);
    }

    private String toJson(DeviceLifecycleRecord record) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("connectionString", record.descriptor().connectionString());
        config.put("enabled", record.descriptor().enabled());
        config.put("location", record.descriptor().location());
        config.put("lifecycleState", record.lifecycleState().name());
        config.put("configurationAsserted", record.configurationAsserted());
        try {
            return mapper.writeValueAsString(config);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize device lifecycle", e);
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(fallback);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Set<DeviceCapability> capabilities(Array sqlArray) {
        if (sqlArray == null) return Set.of();
        try {
            Object raw = sqlArray.getArray();
            if (!(raw instanceof Object[] values)) return Set.of();
            EnumSet<DeviceCapability> result = EnumSet.noneOf(DeviceCapability.class);
            Arrays.stream(values).map(String::valueOf).forEach(value -> {
                try { result.add(DeviceCapability.valueOf(value)); }
                catch (IllegalArgumentException ignored) { }
            });
            return Set.copyOf(result);
        } catch (Exception e) {
            return Set.of();
        }
    }
}
