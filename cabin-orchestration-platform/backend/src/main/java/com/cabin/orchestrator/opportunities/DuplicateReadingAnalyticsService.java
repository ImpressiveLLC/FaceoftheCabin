package com.cabin.orchestrator.opportunities;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * DUPLICATE_READING. A service (a Home Assistant entity, say) and the device it belongs to both being
 * enabled while both report the same measurement: the same reading is carried twice, once by the parent
 * and again by its child. Found 2026-10-29, when the kitchen temperature and humidity sat on the Zigbee
 * parent's tile and again as separate Home Assistant services.
 *
 * Structural, not a rule per measurement: for every enabled device with an enabled parent it intersects
 * the fields each side reports (the {@code reportsFields} attribute), so a measurement nobody has thought
 * of yet is recognised the same way temperature and humidity are. Only housekeeping keys that describe the
 * radio link or the message rather than the world are set aside.
 *
 * One opportunity per parent, listing every child that repeats it. Same never-resolve and
 * never-overwrite rules as the other scans: it creates a row or refreshes an open one, and a person's
 * resolution holds until the set of duplicating children changes.
 */
@Service
public class DuplicateReadingAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(DuplicateReadingAnalyticsService.class);

    /** Keys that describe the link or the message, not a measurement. Everything else a device reports counts. */
    static final Set<String> NOT_A_MEASUREMENT = Set.of(
        "linkquality", "update", "value", "state", "last_seen", "expectedCheckinMinutes");

    @Value("${cabin.opportunities.duplicateReadings.enabled:true}")
    private boolean enabled;

    private final DeviceRegistry registry;
    private final JdbcOptimizationOpportunityStore store;

    public DuplicateReadingAnalyticsService(DeviceRegistry registry, JdbcOptimizationOpportunityStore store) {
        this.registry = registry;
        this.store = store;
    }

    /** One child repeating some of its parent's readings. */
    record Duplicate(String deviceId, String name, List<String> fields) {}

    @Scheduled(cron = "${cabin.opportunities.duplicateReadings.cron:0 40 * * * *}")
    public void scanForDuplicateReadings() {
        if (!enabled) return;
        List<DeviceStatus> inScope = registry.inScope();
        for (Map.Entry<String, List<Duplicate>> parent : findDuplicates(inScope, registry::get).entrySet()) {
            try {
                evaluate(parent.getKey(), parent.getValue());
            } catch (Exception e) {
                log.warn("Skipping duplicate-reading check for {}: {}", parent.getKey(), e.getMessage());
            }
        }
    }

    /** parentDeviceId -> its enabled children that repeat one or more of its readings. */
    static Map<String, List<Duplicate>> findDuplicates(Collection<DeviceStatus> devices,
                                                       Function<String, DeviceStatus> byId) {
        Map<String, List<Duplicate>> byParent = new LinkedHashMap<>();
        for (DeviceStatus child : devices) {
            Object parentId = child.attributes().get("parentDeviceId");
            if (!isEnabled(child) || parentId == null || String.valueOf(parentId).isBlank()) continue;
            DeviceStatus parent = byId.apply(String.valueOf(parentId).trim());
            if (parent == null || !isEnabled(parent) || !java.util.Objects.equals(parent.location(), child.location())) continue;
            TreeSet<String> shared = new TreeSet<>(measurements(child));
            shared.retainAll(measurements(parent));
            if (shared.isEmpty()) continue;
            byParent.computeIfAbsent(parent.deviceId(), k -> new ArrayList<>())
                .add(new Duplicate(child.deviceId(), child.name(), List.copyOf(shared)));
        }
        byParent.values().forEach(list -> list.sort(Comparator.comparing(Duplicate::deviceId)));
        return byParent;
    }

    private static boolean isEnabled(DeviceStatus status) {
        return Boolean.TRUE.equals(status.attributes().get("enabled"));
    }

    private static Set<String> measurements(DeviceStatus status) {
        Object reported = status.attributes().get("reportsFields");
        if (!(reported instanceof Collection<?> fields)) return Set.of();
        return fields.stream().map(String::valueOf).filter(f -> !NOT_A_MEASUREMENT.contains(f))
            .collect(Collectors.toSet());
    }

    void evaluate(String parentId, List<Duplicate> duplicates) {
        Set<String> childIds = duplicates.stream().map(Duplicate::deviceId).collect(Collectors.toCollection(TreeSet::new));
        // A person's resolution holds until the set of repeating children changes.
        Optional<OptimizationOpportunity> lastResolved = store.findAll(OpportunityStatus.RESOLVED).stream()
            .filter(o -> o.opportunityType() == OpportunityType.DUPLICATE_READING && parentId.equals(o.deviceId()))
            .findFirst();
        if (lastResolved.isPresent() && childIds.equals(childIdsOf(lastResolved.get()))) return;

        DeviceStatus parent = registry.get(parentId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Duplicate d : duplicates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("deviceId", d.deviceId());
            item.put("name", d.name());
            item.put("fields", d.fields());
            items.add(item);
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("parentName", parent == null ? parentId : parent.name());
        evidence.put("duplicates", items);
        evidence.put("recommendations", List.of(
            "Each service listed repeats a reading its parent already reports. Monitoring shows the reading once, on the "
                + "parent's tile, and the chart for the parent includes the service's history, so nothing is lost by "
                + "disabling the service.",
            "Keep a service only if you want it for something the parent does not do, such as a workflow that watches "
                + "that entity. Disabling a service never deletes its history.",
            "Services are removed from this list as soon as they are disabled or no longer repeat the parent."));

        Optional<OptimizationOpportunity> existing = store.findOpenOrAcknowledged(parentId, OpportunityType.DUPLICATE_READING);
        String id = existing.map(OptimizationOpportunity::id).orElseGet(() -> UUID.randomUUID().toString());
        OpportunityStatus status = existing.map(OptimizationOpportunity::status).orElse(OpportunityStatus.OPEN);
        Instant detectedAt = existing.map(OptimizationOpportunity::detectedAt).orElseGet(Instant::now);
        store.save(new OptimizationOpportunity(id, OpportunityType.DUPLICATE_READING, parentId, detectedAt, evidence, status, null));
    }

    private static Set<String> childIdsOf(OptimizationOpportunity opportunity) {
        Object duplicates = opportunity.evidence() == null ? null : opportunity.evidence().get("duplicates");
        Set<String> ids = new TreeSet<>();
        if (duplicates instanceof Collection<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map && map.get("deviceId") != null) ids.add(String.valueOf(map.get("deviceId")));
            }
        }
        return ids;
    }
}
