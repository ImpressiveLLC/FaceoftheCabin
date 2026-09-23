package com.cabin.orchestrator.security.demo;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.DeviceReportingRelationshipRepository;
import com.cabin.orchestrator.devices.model.DeviceReportingRelationship;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * D22 R-DM-6 -- which devices a demo viewer sees as "hidden for Demo viewers".
 *
 * Deviation from R-DM-6 as written: its reports_to rule ("security_presence
 * or occupancy") is applied by measurement type (occupancy, contact) rather
 * than by the resolved Topic, because ReportingTopics also maps water_leak
 * to security_presence -- taken literally, that would hide the leak sensors
 * R-DM-6 itself says must show (they are the core of UC-9).
 */
@Component
public class DemoPresenceClassifier {

    private static final Set<DeviceType> PRESENCE_TYPES =
        Set.of(DeviceType.CAMERA, DeviceType.LOCK, DeviceType.MOTION_SENSOR, DeviceType.CONTACT_SENSOR);

    private static final Set<String> PRESENCE_MEASUREMENTS = Set.of("occupancy", "contact", "presence", "motion");

    // Phone, person and device-tracker entities (companion-app battery,
    // charger, Wi-Fi presence). HA entity ids and names are the only signal
    // available -- there is no DeviceType for a phone.
    private static final Pattern PERSONAL_DEVICE = Pattern.compile(
        "(^|[._\\s-])(person|device_tracker|phone|iphone|pixel|galaxy|mobile_app|companion)([._\\s-]|$)"
            + "|^(person|device_tracker)\\.",
        Pattern.CASE_INSENSITIVE);

    private final DeviceRegistry registry;
    private final DeviceReportingRelationshipRepository relationships;

    public DemoPresenceClassifier(DeviceRegistry registry, DeviceReportingRelationshipRepository relationships) {
        this.registry = registry;
        this.relationships = relationships;
    }

    /** deviceId -> real name for every presence-class device, snapshotted per request. */
    public Map<String, String> presenceDevices() {
        Map<String, List<DeviceReportingRelationship>> byDevice;
        try {
            byDevice = relationships.loadAll();
        } catch (RuntimeException e) {
            byDevice = Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (DeviceStatus d : registry.all()) {
            if (isPresenceClass(d, byDevice.getOrDefault(d.deviceId(), List.of()))) {
                out.put(d.deviceId(), d.name());
            }
        }
        return out;
    }

    static boolean isPresenceClass(DeviceStatus d, List<DeviceReportingRelationship> reports) {
        if (d.type() != null && PRESENCE_TYPES.contains(d.type())) return true;
        if ("home".equalsIgnoreCase(d.location())) return true;
        for (DeviceReportingRelationship r : reports) {
            if (r.measurementType() != null && PRESENCE_MEASUREMENTS.contains(r.measurementType().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return isPersonalDevice(d.deviceId()) || isPersonalDevice(d.name());
    }

    static boolean isPersonalDevice(String text) {
        return text != null && PERSONAL_DEVICE.matcher(text).find();
    }
}
