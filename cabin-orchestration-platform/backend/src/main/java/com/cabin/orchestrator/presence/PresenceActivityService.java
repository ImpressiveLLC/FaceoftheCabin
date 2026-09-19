package com.cabin.orchestrator.presence;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.events.OccupancyEdges;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads OCCUPANCY_SENSOR_ACTIVATED/CLEARED events (see OccupancyEdges) and
 * shapes them for the Security &amp; Presence view. This is occupancy history --
 * whether anyone was in a room, and when -- so the only route to it
 * (PresenceActivityController) sits behind the /api/presence gate and an
 * additional adult/administrator role check (D14).
 */
@Service
public class PresenceActivityService {

    static final int DEFAULT_DAYS = 30;
    static final int MAX_DAYS = 365;
    static final int DEFAULT_VISIT_GAP_MINUTES = 30;

    private final JdbcTemplate jdbc;
    private final DeviceRegistry registry;
    private final ZoneId zone;

    @Autowired
    public PresenceActivityService(JdbcTemplate jdbc, DeviceRegistry registry,
                                   @Value("${cabin.timezone:America/Chicago}") String timezone) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.zone = ZoneId.of(timezone);
    }

    /**
     * @param location optional; when given, only sensors the device registry places in that location. Events carry no
     *                 location column of their own, so this joins against the registry the same way
     *                 EventController's location filter does -- a sensor the registry no longer knows about is
     *                 left out of a location-scoped answer rather than guessed into one.
     */
    public Map<String, Object> activity(String deviceId, String location, Integer daysParam,
                                        Integer visitGapMinutesParam, Instant now) {
        int days = clamp(daysParam, DEFAULT_DAYS, 1, MAX_DAYS);
        int gapMinutes = clamp(visitGapMinutesParam, DEFAULT_VISIT_GAP_MINUTES, 1, 24 * 60);
        LocalDate today = now.atZone(zone).toLocalDate();
        Instant from = today.minusDays(days - 1L).atStartOfDay(zone).toInstant();

        // Sensors that have ever produced an edge, plus when each last did (across all time, not just the window).
        Map<String, Instant> lastEver = new LinkedHashMap<>();
        jdbc.query("""
            SELECT device_id, MAX(time) AS last_at FROM cabin_event
            WHERE event_type = ? AND device_id IS NOT NULL AND (?::text IS NULL OR device_id = ?)
            GROUP BY device_id ORDER BY device_id""",
            rs -> { lastEver.put(rs.getString("device_id"), rs.getTimestamp("last_at").toInstant()); },
            OccupancyEdges.ACTIVATED, deviceId, deviceId);
        if (location != null && !location.isBlank()) {
            Set<String> inLocation = registry.byLocation(location).stream()
                .map(DeviceStatus::deviceId).collect(Collectors.toSet());
            lastEver.keySet().retainAll(inLocation);
        }

        Map<String, List<PresenceActivityCalculator.Edge>> edgesBySensor = new LinkedHashMap<>();
        jdbc.query("""
            SELECT device_id, event_type, time FROM cabin_event
            WHERE event_type IN (?, ?) AND time >= ? AND (?::text IS NULL OR device_id = ?)
            ORDER BY device_id, time""",
            rs -> {
                edgesBySensor.computeIfAbsent(rs.getString("device_id"), k -> new ArrayList<>())
                    .add(new PresenceActivityCalculator.Edge(rs.getTimestamp("time").toInstant(),
                        OccupancyEdges.ACTIVATED.equals(rs.getString("event_type"))));
            },
            OccupancyEdges.ACTIVATED, OccupancyEdges.CLEARED, Timestamp.from(from), deviceId, deviceId);

        List<Map<String, Object>> sensors = new ArrayList<>();
        for (Map.Entry<String, Instant> e : lastEver.entrySet()) {
            String id = e.getKey();
            var summary = PresenceActivityCalculator.summarize(
                edgesBySensor.getOrDefault(id, List.of()), today, days, zone, Duration.ofMinutes(gapMinutes), now);
            DeviceStatus device = registry.get(id);

            Map<String, Object> s = new LinkedHashMap<>();
            s.put("deviceId", id);
            s.put("name", device != null && device.name() != null ? device.name() : id);
            s.put("location", device != null ? device.location() : null);
            s.put("battery", device != null && device.attributes() != null ? device.attributes().get("battery") : null);
            s.put("lastSeen", device != null && device.lastSeen() != null ? device.lastSeen().toString() : null);
            s.put("lastActivation", e.getValue().toString());
            s.put("daysSinceLastActivity",
                (int) ChronoUnit.DAYS.between(e.getValue().atZone(zone).toLocalDate(), today));
            s.put("totals", Map.of(
                "activations", summary.activations(),
                "visits", summary.visits(),
                "activeMinutes", summary.activeMinutes(),
                "activeDays", summary.activeDays(),
                "days", days));
            s.put("byDay", summary.days().stream().map(d -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("date", d.date().toString());
                m.put("activations", d.activations());
                m.put("visits", d.visits());
                m.put("activeMinutes", d.activeMinutes());
                m.put("firstAt", d.firstAt() == null ? null : d.firstAt().toString());
                m.put("lastAt", d.lastAt() == null ? null : d.lastAt().toString());
                return m;
            }).toList());
            s.put("byHour", Arrays.stream(summary.activationsByHour()).boxed().toList());
            s.put("recent", summary.recent().stream().map(Instant::toString).toList());
            sensors.add(s);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", now.toString());
        body.put("timezone", zone.getId());
        body.put("days", days);
        body.put("visitGapMinutes", gapMinutes);
        body.put("sensors", sensors);
        return body;
    }

    private static int clamp(Integer v, int dflt, int lo, int hi) {
        return v == null ? dflt : Math.max(lo, Math.min(hi, v));
    }
}
