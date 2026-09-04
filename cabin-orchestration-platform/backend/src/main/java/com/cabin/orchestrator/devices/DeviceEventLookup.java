package com.cabin.orchestrator.devices;

import java.time.Instant;

/**
 * The one CabinEventService capability DeviceHealthMonitor needs -- kept as
 * a narrow interface (not a direct dependency on the concrete class) so
 * unit tests can fake it without a real JdbcTemplate/Postgres, matching
 * this codebase's established "small collaborator interface + fake" test
 * pattern (see DeviceReportingRelationshipRepository's own no-op fakes).
 */
public interface DeviceEventLookup {
    /** True if any event whose eventType starts with eventTypePrefix exists for deviceId at or after since. */
    boolean hasRecentEvent(String deviceId, String eventTypePrefix, Instant since);
}
