package com.cabin.orchestrator.events;

import com.cabin.orchestrator.devices.DeviceEventLookup;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.model.ConfirmationSource;
import com.cabin.orchestrator.devices.model.D7MeasurementTypes;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
public class CabinEventService implements DeviceEventLookup {

    private final JdbcTemplate jdbc;
    private final DeviceReportingRelationshipRepository reportingRelationshipRepository;
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Autowired
    public CabinEventService(JdbcTemplate jdbc, DeviceReportingRelationshipRepository reportingRelationshipRepository) {
        this.jdbc = jdbc;
        this.reportingRelationshipRepository = reportingRelationshipRepository;
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

    /** Convenience constructor for isolated unit tests that don't care about D7 persistence. */
    public CabinEventService(JdbcTemplate jdbc) {
        this(jdbc, new DeviceReportingRelationshipRepository() {
            @Override public void upsert(DeviceReportingRelationship relationship) { }
            @Override public List<DeviceReportingRelationship> findByDevice(String deviceId) { return List.of(); }
            @Override public Map<String, List<DeviceReportingRelationship>> loadAll() { return Map.of(); }
        });
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
     * WSJF bug #2 (Kidde false OFFLINE): DeviceHealthMonitor's own staleness
     * math is entirely a function of DeviceStatus.lastSeen, which for an
     * HA-polled device only refreshes when HomeAssistantDiscoveryService's
     * poll cycle actually sees that exact entity_id in /api/states -- Kidde
     * surfaces as several ungrouped HA entities (see that class's own
     * comment), so one entity going stale/renamed on the HA side can leave
     * its DeviceStatus row stuck while sibling entities for the "same"
     * physical device keep publishing real telemetry under their own
     * deviceIds. Rather than chase the exact HA-side cause, this gives
     * DeviceHealthMonitor a direct, adapter-agnostic way to ask "has
     * anything actually happened for this device recently" before ever
     * declaring it OFFLINE -- telemetry arrival is sufficient evidence of
     * liveness regardless of which adapter or availability mechanism
     * produced it.
     */
    @Override
    public boolean hasRecentEvent(String deviceId, String eventTypePrefix, Instant since) {
        return !recent(deviceId, 1, 0, since, List.of(eventTypePrefix)).isEmpty();
    }

    /**
     * Single event by its own id, or null if it doesn't exist. Added for
     * CameraMediaController's clip-by-time fallback (2026-08-18): a
     * motion-only event (MOTION_ON/OFF) has no frigateEventId to key the
     * existing /events/{frigateEventId}/clip endpoint off, so that
     * fallback needs to look the event back up by cabin-backend's own id
     * to recover its camera/timestamp instead.
     */
    public CabinEvent findById(String eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM cabin_event WHERE event_id = ?", eventId);
        return rows.isEmpty() ? null : fromRow(rows.get(0));
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

    /**
     * Day-bucketed min/avg/max for one numeric TELEMETRY payload field on
     * one device, over the last N days -- built for a real historical
     * trend view (Monitoring panel's "History" section), not raw event
     * replay: recent()'s 200-row cap can't cover weeks of ~10-15min-
     * interval readings, and a chart wants day buckets, not thousands of
     * raw points. jsonb_exists(payload, ?) is used instead of the
     * `payload ? ?` operator -- the bare `?` operator collides with
     * JDBC's own placeholder syntax, a well-known Postgres+JDBC gotcha;
     * the function form sidesteps it entirely. The numeric-format regex
     * guards against a payload field that's sometimes non-numeric (mirrors
     * the same guard the Grafana dashboard panels already use for Kidde's
     * fields, cabin-telemetry.json).
     */
    public List<TelemetryDailyPoint> dailyAggregates(String deviceId, String payloadField, int days) {
        int cappedDays = Math.min(Math.max(days, 1), 366);
        Instant since = Instant.now().minus(java.time.Duration.ofDays(cappedDays));
        String sql = """
            SELECT date_trunc('day', time) AS day,
                   AVG((payload->>?)::numeric) AS avg_val,
                   MIN((payload->>?)::numeric) AS min_val,
                   MAX((payload->>?)::numeric) AS max_val,
                   COUNT(*) AS sample_count
            FROM cabin_event
            WHERE device_id = ?
              AND jsonb_exists(payload, ?)
              AND payload->>? ~ '^-?[0-9]+\\.?[0-9]*$'
              AND time >= ?
            GROUP BY 1 ORDER BY 1
            """;
        return jdbc.queryForList(sql,
                payloadField, payloadField, payloadField, deviceId, payloadField, payloadField,
                java.sql.Timestamp.from(since))
            .stream().map(this::toDailyPoint).toList();
    }

    /**
     * The real, per-device ground truth for "which numeric telemetry
     * fields has this device ever actually logged" -- added 2026-08-27
     * after DeviceType.telemetryFields()'s static per-type guess turned
     * out too blunt: it assumes every TEMPERATURE_SENSOR reports humidity
     * too (true for the Sonoff SNZB-02WD combo units this rule was
     * written around), but z2m-temp_outside_lowest (model SNZB-02LD, also
     * typed TEMPERATURE_SENSOR) has never once logged a humidity reading
     * in its full history -- confirmed via this exact query returning zero
     * rows for it. A type-level assumption can't tell those two apart;
     * only observed data can. Feeds SensorHistoryPanel's field/device
     * picker (App.jsx) so a device only ever appears as an option for a
     * field it has actually reported, not one its type happens to be
     * associated with elsewhere. Same numeric-format guard as
     * dailyAggregates() -- a payload field that's sometimes non-numeric
     * (or a stray non-telemetry key) must not count as "reports this
     * field."
     */
    public Map<String, List<String>> reportedFieldsByDevice() {
        String sql = """
            SELECT device_id, key AS field
            FROM cabin_event, jsonb_object_keys(payload) AS key
            WHERE event_type = 'TELEMETRY'
              AND device_id IS NOT NULL
              AND payload ->> key ~ '^-?[0-9]+\\.?[0-9]*$'
            GROUP BY device_id, key
            """;
        Map<String, List<String>> result = new java.util.LinkedHashMap<>();
        Instant confirmedNow = Instant.now();
        for (Map<String, Object> row : jdbc.queryForList(sql)) {
            String deviceId = (String) row.get("device_id");
            String field = (String) row.get("field");
            result.computeIfAbsent(deviceId, k -> new java.util.ArrayList<>()).add(field);
            // D7 persistence (issue #31): a field this query already proves is
            // being reported gets a real, queryable row -- upsert()'s own
            // priority rule means this can never downgrade an already
            // vendor_spec- or manually-confirmed fact for the same field.
            D7MeasurementTypes.toMeasurementType(field).ifPresent(measurementType ->
                reportingRelationshipRepository.upsert(new DeviceReportingRelationship(
                    deviceId, field, measurementType, ConfirmationSource.EMPIRICAL_OBSERVATION, confirmedNow)));
        }
        return result;
    }

    private TelemetryDailyPoint toDailyPoint(Map<String, Object> row) {
        Object dayVal = row.get("day");
        Instant day = dayVal instanceof java.sql.Timestamp t ? t.toInstant() : Instant.now();
        Number avg = (Number) row.get("avg_val");
        Number min = (Number) row.get("min_val");
        Number max = (Number) row.get("max_val");
        Number count = (Number) row.get("sample_count");
        return new TelemetryDailyPoint(day,
            avg == null ? null : avg.doubleValue(),
            min == null ? null : min.doubleValue(),
            max == null ? null : max.doubleValue(),
            count == null ? 0 : count.longValue());
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
