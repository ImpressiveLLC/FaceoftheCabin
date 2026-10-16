package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;
import com.cabin.orchestrator.security.HouseholdRole;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@link AssistantInventory} the Ask entity resolver works from
 * (D27 R-OC-2, W-67 slice 1).
 *
 * <p>Scope: devices a person has accepted into scope (AVAILABLE or ASSIGNED),
 * never CANDIDATE, DEFERRED or IGNORED. Disabled devices are listed with
 * {@code enabled=false} so the resolver can refuse with a precise reason
 * instead of reporting "not found".
 *
 * <p>Permission filter, applied here so nothing a caller may not see ever
 * reaches model context:
 * <ul>
 *   <li>ADMINISTRATOR: every in-scope device.</li>
 *   <li>ADULT_HOUSEHOLD_MEMBER: the same, minus access-control and camera
 *       devices (D14: they reveal who is home and let a person in).</li>
 *   <li>any other role, or none: nothing (empty inventory).</li>
 * </ul>
 */
@Service
public class AssistantInventoryService {

    private static final Set<DeviceType> ADULT_HIDDEN_TYPES = Set.of(DeviceType.LOCK, DeviceType.CAMERA);

    private final DeviceRegistry registry;
    private final Clock clock;

    public AssistantInventoryService(DeviceRegistry registry) {
        this(registry, Clock.systemUTC());
    }

    AssistantInventoryService(DeviceRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
    }

    /** True when this role may receive any inventory at all. */
    public static boolean mayRead(HouseholdRole role) {
        return role == HouseholdRole.ADMINISTRATOR || role == HouseholdRole.ADULT_HOUSEHOLD_MEMBER;
    }

    public AssistantInventory snapshot(HouseholdRole role) {
        List<AssistantInventory.Entry> entries = new ArrayList<>();
        if (mayRead(role)) {
            for (DeviceStatus raw : registry.inScope()) {
                // get() layers the ontology metadata (area, category) on the raw status.
                DeviceStatus status = registry.get(raw.deviceId());
                if (status == null || !visibleTo(role, status.type())) continue;
                entries.add(toEntry(status));
            }
        }
        entries.sort(Comparator.comparing(AssistantInventory.Entry::entityId));
        return new AssistantInventory(version(entries), clock.instant(), List.copyOf(entries));
    }

    private static boolean visibleTo(HouseholdRole role, DeviceType type) {
        return role == HouseholdRole.ADMINISTRATOR || !ADULT_HIDDEN_TYPES.contains(type);
    }

    private AssistantInventory.Entry toEntry(DeviceStatus status) {
        Map<String, Object> attrs = status.attributes();
        Optional<DeviceDescriptor> descriptor = registry.descriptor(status.deviceId());
        String name = descriptor.map(DeviceDescriptor::name).orElse(status.name());
        // Aliases come only from names already stored; there is no alias store yet (W-68).
        Set<String> aliases = new LinkedHashSet<>();
        if (status.name() != null && !status.name().isBlank() && !status.name().equalsIgnoreCase(name)) {
            aliases.add(status.name().trim());
        }
        List<String> capabilities = descriptor
            .map(d -> d.capabilities().stream().map(DeviceCapability::name).sorted().toList())
            .orElse(List.of());
        return new AssistantInventory.Entry(
            status.deviceId(),
            name,
            List.copyOf(aliases),
            status.location(),
            text(attrs.get("area")),
            text(attrs.get("room")),
            status.type().name(),
            status.type().category().name(),
            capabilities,
            registry.lifecycleState(status.deviceId()).name(),
            descriptor.map(DeviceDescriptor::enabled).orElse(false),
            status.state() == null ? "UNKNOWN" : status.state(),
            status.lastSeen());
    }

    private static String text(Object value) {
        if (!(value instanceof String s) || s.isBlank()) return null;
        return s.trim();
    }

    /** SHA-256 over every plan-relevant field in a fixed order; lastObserved is excluded on purpose. */
    static String version(List<AssistantInventory.Entry> sortedEntries) {
        StringBuilder canonical = new StringBuilder();
        for (AssistantInventory.Entry e : sortedEntries) {
            canonical.append(String.join("\u001f",
                e.entityId(), String.valueOf(e.name()),
                String.join(",", e.aliases().stream().map(a -> a.toLowerCase(Locale.ROOT)).toList()),
                String.valueOf(e.site()), String.valueOf(e.area()), String.valueOf(e.room()),
                e.deviceClass(), String.join(",", e.capabilities()), e.lifecycle(),
                String.valueOf(e.enabled()), e.availability())).append('\n');
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }
}
