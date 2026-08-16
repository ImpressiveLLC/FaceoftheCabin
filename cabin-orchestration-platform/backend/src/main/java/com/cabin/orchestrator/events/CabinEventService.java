package com.cabin.orchestrator.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Same JdbcTemplate pattern as DeviceDisplayConfigService/FamilyNoteService/
 * ChoreCompletionService — the cabin_event table already existed (created
 * by an earlier session anticipating this) but had no reader or writer
 * until now; init() below is a no-op CREATE TABLE IF NOT EXISTS against
 * that existing schema, kept for consistency with the established pattern.
 */
@Service
public class CabinEventService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    public CabinEventService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS cabin_event (
              event_id   TEXT PRIMARY KEY,
              time       TIMESTAMPTZ NOT NULL DEFAULT now(),
              device_id  TEXT,
              event_type TEXT NOT NULL,
              severity   TEXT NOT NULL,
              payload    JSONB
            )""");
        jdbc.execute("CREATE INDEX IF NOT EXISTS cabin_event_time_idx ON cabin_event (\"time\" DESC)");
    }

    public void save(CabinEvent event) {
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            VALUES (?,?,?,?,?,?::jsonb)
            ON CONFLICT (event_id) DO NOTHING""",
            event.eventId(), java.sql.Timestamp.from(event.timestamp()), event.sourceDeviceId(),
            event.eventType(), event.severity(), toJson(event.payload()));
    }

    /**
     * Same shape as save(), but updates the existing row on a collision
     * instead of silently keeping whatever was there first. Exists
     * specifically for Frigate-sourced events keyed by a deterministic id
     * (frigate:{frigateEventId}, see MqttBridgeService/
     * FrigateEventReconciliationService) -- the same underlying Frigate
     * tracked object is legitimately re-reported multiple times as it
     * evolves (hasClip/hasSnapshot can start false and flip true once the
     * clip finishes encoding; MQTT and the periodic REST reconciliation
     * can both observe the same event independently). DO NOTHING would
     * mean whichever report arrived first wins forever, even if it was
     * the least complete one -- the opposite of what's wanted here.
     * save() above stays untouched for every other event source
     * (motion/telemetry/direct-injection), which use a fresh random UUID
     * per event and have no reason to ever collide, let alone want a
     * later "duplicate" to overwrite an earlier one.
     */
    public void upsert(CabinEvent event) {
        jdbc.update("""
            INSERT INTO cabin_event (event_id, time, device_id, event_type, severity, payload)
            VALUES (?,?,?,?,?,?::jsonb)
            ON CONFLICT (event_id) DO UPDATE SET
                time = EXCLUDED.time,
                device_id = EXCLUDED.device_id,
                event_type = EXCLUDED.event_type,
                severity = EXCLUDED.severity,
                payload = EXCLUDED.payload""",
            event.eventId(), java.sql.Timestamp.from(event.timestamp()), event.sourceDeviceId(),
            event.eventType(), event.severity(), toJson(event.payload()));
    }

    /** Most recent events, newest first, optionally filtered by camera/device and a lookback window. */
    public List<CabinEvent> recent(String deviceId, int limit, Instant since) {
        return recent(deviceId, limit, 0, since, null);
    }

    /**
     * Same as recent(), plus real server-side pagination (offset) and an
     * eventType-prefix filter -- added 2026-08-07 (Phase 7 §4a/§4c) so
     * CameraEventsPanel can ask for "just camera events" (DETECTION_*,
     * MOTION_*) directly instead of fetching everything and filtering
     * client-side (isCameraEvent, App.jsx), which was the original fast
     * fix. eventTypePrefixes is nullable/empty for "no filter", matching
     * every other optional-filter param on this method.
     */
    public List<CabinEvent> recent(String deviceId, int limit, int offset, Instant since, List<String> eventTypePrefixes) {
        StringBuilder sql = new StringBuilder("SELECT * FROM cabin_event WHERE time >= ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(java.sql.Timestamp.from(since));
        if (deviceId != null && !deviceId.isBlank()) {
            sql.append(" AND device_id = ?");
            args.add(deviceId);
        }
        if (eventTypePrefixes != null && !eventTypePrefixes.isEmpty()) {
            sql.append(" AND (");
            for (int i = 0; i < eventTypePrefixes.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("event_type LIKE ?");
                args.add(eventTypePrefixes.get(i) + "%");
            }
            sql.append(")");
        }
        sql.append(" ORDER BY time DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(sql.toString(), args.toArray())
            .stream().map(this::fromRow).toList();
    }

    private CabinEvent fromRow(Map<String, Object> row) {
        Object timeVal = row.get("time");
        Instant ts = timeVal instanceof java.sql.Timestamp t ? t.toInstant() : Instant.now();
        return new CabinEvent(
            (String) row.get("event_id"),
            (String) row.get("device_id"),
            (String) row.get("event_type"),
            (String) row.get("severity"),
            ts,
            parseJson(row.get("payload")));
    }

    // The jsonb column comes back from the JDBC driver as a PGobject, not a
    // String — same "the driver's default row mapping doesn't match what
    // the column actually is" issue would trip up any jsonb column read via
    // queryForList's generic Map<String,Object>, not specific to this table.
    private Map<String, Object> parseJson(Object payload) {
        if (payload == null) return Map.of();
        String json = payload.toString();
        if (json.isBlank()) return Map.of();
        try { return mapper.readValue(json, new TypeReference<>() {}); }
        catch (Exception e) { return Map.of(); }
    }

    private String toJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) return "{}";
        try { return mapper.writeValueAsString(payload); }
        catch (Exception e) { return "{}"; }
    }
}
