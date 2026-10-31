package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;

import java.util.Locale;
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
 *   <li>any other device whose id, name or entity id contains "lock" accepts no command from here.</li>
 * </ul>
 * Automation is separate: workflows reach {@code DeviceRegistry.sendCommand} through their own gate (CommandCatalogService),
 * so a leak workflow that closes the valve is not affected.
 */
public final class DeviceCommandGuard {

    /** Shared with HaServiceController so the two paths can never disagree about what is denied. */
    public static final Pattern DENIED_ENTITY = Pattern.compile("valve|lock");

    private static final String SECURE_LOCK_COMMAND = "lock.lock";

    private DeviceCommandGuard() {}

    /**
     * Why a dashboard command to this device is refused, or empty when it may go on to the registry's own checks.
     *
     * <p>The command is sent to the descriptor's {@code connectionString} (the Home Assistant entity or the Zigbee2MQTT
     * friendly name), which anyone who can create or edit a device chooses, so the text checked includes it: a harmless id
     * and name cannot hide a valve. A device that has a descriptor but no status, or the reverse, fails closed. A device
     * with neither is unknown and is left to the registry, which refuses it.
     *
     * <p>A lock must also carry no payload: the Home Assistant path forwards it as service data and the MQTT path publishes
     * it as the message, so on MQTT the command string is only a log hint. On the {@code mqtt} adapter the one accepted
     * payload is exactly {@code {"state":"LOCK"}} (what the adapter needs to lock at all).
     */
    public static Optional<String> denial(DeviceDescriptor descriptor, DeviceStatus device, String command, Object payload) {
        if (descriptor == null && device == null) return Optional.empty();
        if (descriptor == null || device == null) {
            return Optional.of("This device cannot be commanded from the dashboard: its configuration is incomplete.");
        }
        String text = (nullToEmpty(device.deviceId()) + " " + nullToEmpty(device.name()) + " "
            + nullToEmpty(device.attributes() == null ? null : device.attributes().get("entityId")) + " "
            + nullToEmpty(descriptor.deviceId()) + " " + nullToEmpty(descriptor.name()) + " "
            + nullToEmpty(descriptor.connectionString())).toLowerCase(Locale.ROOT);
        if (text.contains("valve")) {
            return Optional.of("A valve cannot be commanded from the dashboard.");
        }
        if (device.type() == DeviceType.LOCK || descriptor.type() == DeviceType.LOCK) {
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

    private static boolean lockPayloadAllowed(String protocolAdapter, Object payload) {
        if (payload == null) return true;
        if (!"mqtt".equals(protocolAdapter)) return false;
        return payload instanceof Map<?, ?> map && map.size() == 1 && "LOCK".equals(map.get("state"));
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
