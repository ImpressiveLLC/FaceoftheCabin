package com.cabin.orchestrator.alerts;

/**
 * Builds and parses the unified alertKey that alert_acknowledgment and
 * alert_history are keyed by (see AlertAcknowledgment's own doc):
 * "device:{deviceId}:{alarm|missed-checkin}" (ActiveAlert.alertId verbatim)
 * and "automation:{ruleId}:{deviceId|none}".
 *
 * The client derives the automation form itself (App.jsx
 * normalizeAutomationAlert) from the raw ruleId. This class additionally
 * collapses WORKFLOW_UNCONFIRMED_{executionId} to one rule, because the
 * executionId makes every occurrence a brand-new ruleId and repeats could
 * never be counted otherwise. The client key does not do that yet, so
 * ignoring one unconfirmed-command alert does not suppress the next.
 */
public final class AlertKeys {

    public record Parsed(String kind, String deviceId, String ruleId, String condition) {}

    private static final String UNCONFIRMED_PREFIX = "WORKFLOW_UNCONFIRMED_";

    private AlertKeys() {}

    public static String automation(String ruleId, String deviceId) {
        String rule = ruleId == null ? "unknown" : (ruleId.startsWith(UNCONFIRMED_PREFIX) ? "WORKFLOW_UNCONFIRMED" : ruleId);
        return "automation:" + rule + ":" + (deviceId == null || deviceId.isBlank() ? "none" : deviceId);
    }

    /** Never null; an unrecognized key comes back as kind "unknown" with nothing else set. */
    public static Parsed parse(String alertKey) {
        if (alertKey == null) return new Parsed("unknown", null, null, null);
        boolean device = alertKey.startsWith("device:");
        boolean automation = alertKey.startsWith("automation:");
        if (!device && !automation) return new Parsed("unknown", null, null, null);
        String rest = alertKey.substring(device ? "device:".length() : "automation:".length());
        int split = rest.lastIndexOf(':');
        if (split < 0) return new Parsed(device ? "device" : "automation", null, null, null);
        String head = rest.substring(0, split);
        String tail = rest.substring(split + 1);
        if (device) {
            return new Parsed("device", head, null, conditionFromSuffix(tail));
        }
        return new Parsed("automation", "none".equals(tail) ? null : tail, head, null);
    }

    /** device-key = "device:{deviceId}:{suffix}" -- the suffix ActiveAlertService uses, mapped to its condition name. */
    public static String conditionFromSuffix(String suffix) {
        if ("alarm".equals(suffix)) return "DEVICE_ALARM";
        if ("missed-checkin".equals(suffix)) return "MISSED_CHECKIN";
        return suffix;
    }
}
