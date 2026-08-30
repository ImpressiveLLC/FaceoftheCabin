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
}
