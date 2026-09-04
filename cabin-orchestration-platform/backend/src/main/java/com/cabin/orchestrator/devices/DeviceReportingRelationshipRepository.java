package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;

import java.util.List;
import java.util.Map;

/**
 * Persists D7 reporting relationships (issue #31) -- the first-class,
 * queryable version of what CabinEventService.reportedFieldsByDevice() and
 * Zigbee2MqttAdapter.extractVendorReportedFields() already compute live
 * today. upsert() never lets a lower-priority ConfirmationSource downgrade
 * an already-confirmed higher-priority one (see the Jdbc implementation's
 * own comment).
 */
public interface DeviceReportingRelationshipRepository {
    void upsert(DeviceReportingRelationship relationship);
    List<DeviceReportingRelationship> findByDevice(String deviceId);
    Map<String, List<DeviceReportingRelationship>> loadAll();

    /**
     * D13 (Service-Level Data Lineage): sets or clears (blank -> null) the
     * display_label on an EXISTING (deviceId, semanticField) row -- the one
     * explicit curation action, deliberately separate from upsert()'s
     * routine auto-observation path (which never touches this column, see
     * JdbcDeviceReportingRelationshipRepository's own comment). No-op by
     * default so every pre-D13 fake implementation (Zigbee2MqttAdapter's/
     * CabinEventService's own no-op test collaborators) keeps compiling
     * unchanged.
     */
    default void setDisplayLabel(String deviceId, String semanticField, String displayLabel) {}
}
