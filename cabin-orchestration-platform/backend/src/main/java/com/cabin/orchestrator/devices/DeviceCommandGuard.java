package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The valve and lock denial for commands sent from the dashboard, in one place (safety order, 2026-10-31).
 *
 * <p>It used to live on two paths only: the row On/Off button and {@code /api/ha/services} ({@code HaServiceController}).
 * The Turn on / Turn off buttons in a device's details call {@code POST /api/devices/{id}/command}, which checked only
 * Enabled and Assigned, so an Assigned and Enabled main water valve (a Home Assistant switch) could be reopened from there,
 * outside the guard CLAUDE.md and the W-75 spec rely on ("no valve-open or unlock capability"). Every dashboard command path
 * now asks this class.
 *
 * <p>Rules, by the same {@code valve|lock} text match the other paths use (so it over-denies a name such as "clock" rather than
 * under-denies; that is the safe direction):
 * <ul>
 *   <li>a <b>valve</b> (any id, name, entity id or connection string containing "valve") accepts no command from here, open or close;</li>
 *   <li>a device of type {@code LOCK} accepts only {@code lock.lock} (the secure direction): never an unlock or any other command;</li>
 *   <li>any other device whose id, name or entity id contains "lock" accepts no command from here;</li>
 *   <li>a device on the {@code ha_rest} adapter must name exactly one entity ({@code domain.object}, lower case), and its command must be
 *       {@code domain.service} in that same domain, from a short allow-list of domains (switch, light, cover, input_boolean, lock): the adapter
 *       posts the connection string as the entity id, so a group, an {@code all} target, a script or a cross-domain command is refused;</li>
 *   <li>a device reached by the same connection string, or by the same Zigbee2MQTT name or address, is judged as well (see {@link Resolution}).</li>
 * </ul>
 * Automation is separate: workflows reach {@code DeviceRegistry.sendCommand} through their own gate (CommandCatalogService),
 * so a leak workflow that closes the valve is not affected.
 */
public final class DeviceCommandGuard {

    /** Shared with HaServiceController so the two paths can never disagree about what is denied. */
    public static final Pattern DENIED_ENTITY = Pattern.compile("valve|lock");

    private static final String SECURE_LOCK_COMMAND = "lock.lock";
    private static final Pattern HA_ENTITY = Pattern.compile("^([a-z0-9_]+)\\.[a-z0-9_]+$");
    private static final Pattern HA_COMMAND = Pattern.compile("^([a-z0-9_]+)\\.[a-z0-9_]+$");
    /** Domains the dashboard command route may call on a Home Assistant entity. */
    private static final Set<String> HA_COMMAND_DOMAINS = Set.of("switch", "light", "cover", "input_boolean", "lock");

    private DeviceCommandGuard() {}

    /**
     * A registered device a command actually reaches: the target itself, any device sharing its connection string, and
     * (for Zigbee2MQTT) the device owning the name or IEEE address it is sent to. {@code inferredType} and
     * {@code inferredCapabilities} are what Zigbee2MQTT discovery said the device is, which is kept even if the registered type is edited later; both may be null.
     */
    public record Reached(String deviceId, DeviceDescriptor descriptor, DeviceStatus status,
                          DeviceType inferredType, Set<DeviceCapability> inferredCapabilities) {}

    /** Everything a command reaches, and whether an MQTT target resolved to no known device at all. */
    public record Resolution(List<Reached> reached, boolean unresolved) {
        public static final Resolution NONE = new Resolution(List.of(), false);
    }

    /**
     * Why a dashboard command to this device is refused, or empty when it may go on to the registry's own checks.
     *
     * <p>The command is sent to the descriptor's {@code connectionString} (the Home Assistant entity or the Zigbee2MQTT
     * friendly name), which whoever creates or edits a device chooses, so the text checked includes it, not only the id and name. A device that has a descriptor but no status, or the reverse, fails closed. A device
     * with neither is unknown and is left to the registry, which refuses it.
     *
     * <p>A lock must also carry no payload: the Home Assistant path forwards it as service data and the MQTT path publishes
     * it as the message, so on MQTT the command string is only a log hint. On the {@code mqtt} adapter the one accepted
     * payload is exactly {@code {"state":"LOCK"}} (what the adapter needs to lock at all).
     */
    public static Optional<String> denial(DeviceDescriptor descriptor, DeviceStatus device, String command, Object payload) {
        return denial(descriptor, device, command, payload, Resolution.NONE);
    }

    /**
     * Same, plus every device the command reaches: a command is judged by each registered device it is delivered to, not only
     * by the labels on the target. Any other device reached that is a valve or lock refuses the command, and an MQTT target
     * that resolves to no known device is refused.
     */
    public static Optional<String> denial(DeviceDescriptor descriptor, DeviceStatus device, String command, Object payload,
                                          Resolution resolution) {
        if (descriptor == null && device == null) return Optional.empty();
        if (descriptor == null || device == null) {
            return Optional.of("This device cannot be commanded from the dashboard: its configuration is incomplete.");
        }
        String text = (nullToEmpty(device.deviceId()) + " " + nullToEmpty(device.name()) + " "
            + nullToEmpty(device.attributes() == null ? null : device.attributes().get("entityId")) + " "
            + nullToEmpty(descriptor.deviceId()) + " " + nullToEmpty(descriptor.name()) + " "
            + nullToEmpty(descriptor.connectionString())).toLowerCase(Locale.ROOT);
        if ("ha_rest".equals(descriptor.protocolAdapter())) {
            Optional<String> shape = haTargetDenial(descriptor.connectionString(), command);
            if (shape.isPresent()) return shape;
        }
        String selfId = descriptor.deviceId();
        if (resolution != null && resolution.unresolved()) {
            return Optional.of("This device's Zigbee2MQTT target is not a known device, so it cannot be commanded from the dashboard.");
        }
        if (resolution != null) {
            for (Reached other : resolution.reached()) {
                if (!selfId.equals(other.deviceId()) && isValveOrLock(other.descriptor(), other.status(), other.inferredType(), other.inferredCapabilities())) {
                    return Optional.of("This command would reach a valve or lock, which cannot be commanded from the dashboard.");
                }
            }
        }
        DeviceType selfInferred = null;
        Set<DeviceCapability> selfCaps = null;
        if (resolution != null) {
            for (Reached r : resolution.reached()) {
                if (selfId.equals(r.deviceId())) { selfInferred = r.inferredType(); selfCaps = r.inferredCapabilities(); }
            }
        }
        if (text.contains("valve") || device.type() == DeviceType.WATER_VALVE || descriptor.type() == DeviceType.WATER_VALVE
                || selfInferred == DeviceType.WATER_VALVE) {
            return Optional.of("A valve cannot be commanded from the dashboard.");
        }
        if (device.type() == DeviceType.LOCK || descriptor.type() == DeviceType.LOCK || selfInferred == DeviceType.LOCK
                || (selfCaps != null && selfCaps.contains(DeviceCapability.ACCESS_CONTROL))
                || (descriptor.capabilities() != null && descriptor.capabilities().contains(DeviceCapability.ACCESS_CONTROL))) {
            if (!SECURE_LOCK_COMMAND.equals(command)) {
                return Optional.of("A lock can only be locked from the dashboard, never unlocked.");
            }
            if (!lockPayloadAllowed(descriptor.protocolAdapter(), payload)) {
                return Optional.of("A lock command from the dashboard carries no extra data.");
            }
            return Optional.empty();
        }
        if (DENIED_ENTITY.matcher(text).find()) {
            return Optional.of("A lock cannot be commanded from the dashboard.");
        }
        return Optional.empty();
    }

    /**
     * True when this device is, by its id, name, entity id, connection string or type, a valve or a lock. The dashboard's
     * Home Assistant service route (/api/ha/services) names only an entity id, so it asks this about the registered
     * device that owns that entity: a valve whose entity id does not say "valve" (a renamed entity) is still refused.
     * Either argument may be null.
     */
    public static boolean isValveOrLock(DeviceDescriptor descriptor, DeviceStatus device) {
        return isValveOrLock(descriptor, device, null, null);
    }

    public static boolean isValveOrLock(DeviceDescriptor descriptor, DeviceStatus device, DeviceType inferredType,
                                        Set<DeviceCapability> inferredCapabilities) {
        for (DeviceType type : new DeviceType[] {descriptor == null ? null : descriptor.type(), device == null ? null : device.type(), inferredType}) {
            if (type == DeviceType.LOCK || type == DeviceType.WATER_VALVE) return true;
        }
        if (inferredCapabilities != null && inferredCapabilities.contains(DeviceCapability.ACCESS_CONTROL)) return true;
        if (descriptor != null && descriptor.capabilities() != null && descriptor.capabilities().contains(DeviceCapability.ACCESS_CONTROL)) return true;
        String text = (descriptor == null ? "" : nullToEmpty(descriptor.deviceId()) + " " + nullToEmpty(descriptor.name()) + " "
            + nullToEmpty(descriptor.connectionString()))
            + " " + (device == null ? "" : nullToEmpty(device.deviceId()) + " " + nullToEmpty(device.name()) + " "
            + nullToEmpty(device.attributes() == null ? null : device.attributes().get("entityId")));
        return DENIED_ENTITY.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /** One entity in domain.object form, and a command in that same allowed domain; anything else (all, groups, lists, scripts) is refused. */
    private static Optional<String> haTargetDenial(String connectionString, String command) {
        java.util.regex.Matcher entity = HA_ENTITY.matcher(connectionString == null ? "" : connectionString);
        java.util.regex.Matcher cmd = HA_COMMAND.matcher(command == null ? "" : command);
        if (!entity.matches()) {
            return Optional.of("This device must name exactly one Home Assistant entity to be commanded from the dashboard.");
        }
        String domain = entity.group(1);
        if (!cmd.matches() || !cmd.group(1).equals(domain) || !HA_COMMAND_DOMAINS.contains(domain)) {
            return Optional.of("That command is not allowed for this device from the dashboard.");
        }
        return Optional.empty();
    }

    private static boolean lockPayloadAllowed(String protocolAdapter, Object payload) {
        if (payload == null) return true;
        if (!"mqtt".equals(protocolAdapter)) return false;
        return payload instanceof Map<?, ?> map && map.size() == 1 && "LOCK".equals(map.get("state"));
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
