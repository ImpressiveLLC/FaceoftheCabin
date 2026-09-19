package com.cabin.orchestrator.events;

import java.util.Optional;

/**
 * The last occupancy value a sensor is known to have reported. The adapter
 * keeps this in memory while running, but the device registry is not
 * Postgres-backed, so after a restart the previous value is unknown until the
 * first message; without this, a sensor whose cached state is already
 * {@code true} would look like a fresh activation on its next battery report.
 */
public interface OccupancyHistory {
    Optional<Boolean> lastKnown(String deviceId);
}
