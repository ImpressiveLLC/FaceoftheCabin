package com.cabin.orchestrator.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a motion sensor's occupancy reading into a durable state-change event.
 *
 * <p>Why this exists: every Zigbee message is logged as a TELEMETRY event that
 * carries the sensor's whole current state, so {@code occupancy} is repeated on
 * every battery / link-quality report, not just on real motion -- counting
 * TELEMETRY rows with occupancy=true overstated activity about 2x (Sep 15 2026:
 * 91 true rows for 43 real activations). Presence history needs the
 * <em>transitions</em>, and it needs them somewhere TelemetryArchivalService will
 * not age out (it only touches event_type = TELEMETRY; discrete state-change
 * events such as SECURITY_ARMED_CHANGED are deliberately left alone, and so are
 * these).
 *
 * <p>The event type deliberately avoids the {@code MOTION_} prefix: the Camera
 * Events panel already claims {@code DETECTION_,MOTION_} for Frigate/Blink.
 *
 * <p>The id is deterministic ({@code occ:{device}:{epochMillis}:{A|C}}) so the
 * same transition produces the same row whether it was detected live or
 * reconstructed from history by {@code PresenceActivityBackfill}; a duplicate is
 * absorbed by cabin_event's primary key.
 */
public final class OccupancyEdges {

    public static final String ACTIVATED = "OCCUPANCY_SENSOR_ACTIVATED";
    public static final String CLEARED = "OCCUPANCY_SENSOR_CLEARED";

    private OccupancyEdges() {}

    /**
     * The edge this reading represents, or empty when it is a repeat.
     *
     * @param previous the last known occupancy, or null when unknown
     * @param current  the reading just received
     */
    public static Optional<CabinEvent> edge(String deviceId, Boolean previous, Boolean current, Instant at, String location) {
        if (current == null) return Optional.empty();
        String type;
        if (current && !Boolean.TRUE.equals(previous)) {
            type = ACTIVATED;
        } else if (!current && Boolean.TRUE.equals(previous)) {
            type = CLEARED;
        } else {
            return Optional.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sensor", deviceId);
        if (location != null && !location.isBlank()) payload.put("location", location);
        return Optional.of(new CabinEvent(eventId(deviceId, at, type), deviceId, type, "INFO", at, payload));
    }

    public static String eventId(String deviceId, Instant at, String type) {
        return "occ:" + deviceId + ":" + at.toEpochMilli() + ":" + (ACTIVATED.equals(type) ? "A" : "C");
    }
}
