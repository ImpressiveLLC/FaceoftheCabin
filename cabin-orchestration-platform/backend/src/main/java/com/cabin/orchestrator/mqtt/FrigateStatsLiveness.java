package com.cabin.orchestrator.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Reads Frigate's periodic stats message (cabin/camera/stats, about once a
 * minute) for the names of the cameras Frigate is currently running.
 *
 * Why this exists (2026-10-19): a motion-gated Blink camera (driveway,
 * home_aldrich_front) has no continuous stream, so Frigate's motion and
 * object-count topics -- the only things that refreshed its lastSeen -- stay
 * silent on a quiet day, and every backend restart (which wipes the in-memory
 * registry) made DeviceHealthMonitor mark it off-schedule five minutes later.
 * Frigate's stats list every camera it is running, including an idle Blink one
 * at camera_fps 0.0, so presence in the list is the heartbeat; the fps value is
 * deliberately not read, because 0.0 is normal for a motion-gated camera.
 *
 * What it proves: Frigate is up and has the camera enabled. It does not prove
 * that Blink's cloud or blinkbridge is healthy.
 */
final class FrigateStatsLiveness {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FrigateStatsLiveness() {}

    /** Camera names under "cameras" in a Frigate stats payload; empty for anything unparseable. */
    static Set<String> cameraNames(String payload) {
        Set<String> names = new LinkedHashSet<>();
        if (payload == null || payload.isBlank()) return names;
        try {
            JsonNode cameras = MAPPER.readTree(payload).path("cameras");
            if (!cameras.isObject()) return names;
            Iterator<String> it = cameras.fieldNames();
            while (it.hasNext()) names.add(it.next());
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
        return names;
    }
}
