package com.cabin.orchestrator.opportunities;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * W-65. What to look at when an alert keeps firing or keeps being silenced.
 * A deterministic table, not a model call: the answer depends only on what
 * kind of alert it is, and every knob named here is one that actually
 * exists. The same guidance, in prose, is the "An alert keeps firing" section
 * of docs/ai-assistant/user-guide/operations.md -- keep the two in step.
 *
 * Safety-class alerts (a safety-category device such as a water-leak, smoke
 * or CO sensor; any DEVICE_ALARM; the SAFETY_ALARM and FREEZE_RISK rules)
 * are never told to "tune it down". A repeat there is evidence to
 * investigate, and silencing it is not a fix. This is the same stance
 * OptimizationAnalyticsService took in refusing to call the mechanical-room
 * heater's constant draw "waste".
 */
public final class AlertRecommendations {

    /** One alert's tally for one device over the window. kind is "device" or "automation". */
    public record AlertNoise(String alertKey, String kind, String ruleId, String condition, String deviceId,
                             long raised, long silenced, Instant firstAt, Instant lastAt) {}

    private static final Set<String> SAFETY_RULES = Set.of("SAFETY_ALARM", "FREEZE_RISK");
    private static final Set<String> PRESENCE_RULES = Set.of("MOTION_WHILE_UNOCCUPIED", "LOCK_UNLOCKED");

    private AlertRecommendations() {}

    public static boolean isSafetyClass(AlertNoise a, boolean safetyDevice) {
        // Set.of(...).contains(null) throws, and a device-kind alert has no ruleId.
        return safetyDevice || "DEVICE_ALARM".equals(a.condition())
            || (a.ruleId() != null && SAFETY_RULES.contains(a.ruleId()));
    }

    public static List<String> forAlert(AlertNoise a, boolean safetyDevice, int minSilences) {
        List<String> out = new ArrayList<>();
        boolean silencedOften = a.silenced() >= minSilences;

        if (isSafetyClass(a, safetyDevice)) {
            out.add("This is a safety alert. Repeats are a reason to find the cause (sensor placement, a slow leak, a failing "
                + "battery, a genuinely cold spot, a faulty probe) before changing anything. Silencing it is not a fix.");
            if (silencedOften) {
                out.add("It has been silenced " + a.silenced() + " times. If it is a nuisance because the cause is known and "
                    + "fixed, fix the cause; if the cause is unknown, that is the thing to chase.");
            }
            return out;
        }

        String rule = a.ruleId() == null ? "" : a.ruleId();
        if ("MISSED_CHECKIN".equals(a.condition())) {
            out.add("Compare how often this device really reports with the check-in window on its device page (\"Expected "
                + "within ... min\"). If it normally reports less often than that window, the window is set too tight and it "
                + "will keep looking late.");
            out.add("If it reports on time sometimes and not others, look at its link and its battery: signal quality, "
                + "distance to the nearest router, a battery near the end.");
        } else if (rule.startsWith("WORKFLOW_UNCONFIRMED")) {
            out.add("A command this workflow sent keeps not being confirmed by the device. Check the device itself, its link "
                + "and battery, and whether another automation (Home Assistant, Node-RED) is reverting the command.");
        } else if (rule.startsWith("WORKFLOW_")) {
            out.add("This workflow notifies every time its condition recurs. If the condition itself keeps flapping, give its "
                + "notify action a cooldown. An existing workflow cannot be edited yet, so create the replacement with the "
                + "cooldown and delete the old one.");
            out.add("Check the trigger: a reading that sits right at its threshold will cross it again and again; the "
                + "device may need repositioning rather than the workflow changing.");
        } else if (PRESENCE_RULES.contains(rule)) {
            out.add("This rule depends on knowing who is at the location. If it fires while someone is home, check the "
                + "presence signals first; the rule is usually right and the presence picture wrong.");
        } else if (!rule.isEmpty()) {
            out.add("This is a built-in backend rule; its threshold is a deploy-time setting, not something editable in the "
                + "app. Check whether the reading is genuinely near the threshold (sensor placement, a real condition) "
                + "before asking for a threshold change.");
        } else {
            out.add("Look at what the alert is actually reporting and whether the underlying condition is real and recurring.");
        }

        if (silencedOften) {
            out.add("It has been silenced " + a.silenced() + " times. Decide whether it is worth keeping: retune it as above, "
                + "or retire it. An alert that is routinely silenced is easy to miss on the day it is real.");
        }
        return out;
    }
}
