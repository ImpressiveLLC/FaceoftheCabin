package com.cabin.orchestrator.devices;

import com.cabin.orchestrator.devices.model.DeviceStatus;
import com.cabin.orchestrator.devices.model.DeviceType;

import java.util.Locale;
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
 *   <li>a <b>valve</b> (any id, name or entity id containing "valve") accepts no command from here, open or close;</li>
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

    /** True when the entity id (or any identifying text) is one the dashboard never commands. */
    public static boolean deniedEntity(String text) {
        return text != null && DENIED_ENTITY.matcher(text.toLowerCase(Locale.ROOT)).find();
    }

    /**
     * Why a dashboard command to this device is refused, or empty when it may go on to the registry's own checks.
     * An unknown device has no status here, so it is left to the registry (which refuses it).
     */
    public static Optional<String> denial(DeviceStatus device, String command) {
        if (device == null) return Optional.empty();
        String text = (nullToEmpty(device.deviceId()) + " " + nullToEmpty(device.name()) + " "
            + nullToEmpty(device.attributes() == null ? null : device.attributes().get("entityId"))).toLowerCase(Locale.ROOT);
        if (text.contains("valve")) {
            return Optional.of("A valve cannot be commanded from the dashboard.");
        }
        if (device.type() == DeviceType.LOCK) {
            return SECURE_LOCK_COMMAND.equals(command)
                ? Optional.empty()
                : Optional.of("A lock can only be locked from the dashboard, never unlocked.");
        }
        if (DENIED_ENTITY.matcher(text).find()) {
            return Optional.of("A lock cannot be commanded from the dashboard.");
        }
        return Optional.empty();
    }

    private static String nullToEmpty(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
