package com.cabin.orchestrator.alerts;

import com.cabin.orchestrator.events.CabinEvent;
import com.cabin.orchestrator.events.CabinEventService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The reviewable alert history: alert_history rows merged with the
 * automation alerts already persisted in cabin_event. One fact, one home --
 * automation events are read from where they live, never copied.
 */
@Service
public class AlertHistoryService {

    static final List<String> AUTOMATION_EVENT_TYPES = List.of("AUTOMATION_ALERT", "WORKFLOW_ACTION", "WORKFLOW_UNCONFIRMED");
    private static final int PER_SOURCE_CAP = 500;

    private final JdbcAlertHistoryStore store;
    private final CabinEventService events;

    public AlertHistoryService(JdbcAlertHistoryStore store, CabinEventService events) {
        this.store = store;
        this.events = events;
    }

    /** Logs one ignore / snooze / un-ignore action. */
    public void recordAcknowledgmentAction(String alertKey, String action, String actor, Instant snoozedUntil, Instant occurredAt) {
        store.append(AlertHistoryEntry.ofAcknowledgment(alertKey, action, actor, snoozedUntil, occurredAt));
    }

    public List<AlertHistoryEntry> history(Instant since, String deviceId, int limit, int offset) {
        int want = Math.min(limit + offset, PER_SOURCE_CAP);
        List<AlertHistoryEntry> merged = new ArrayList<>(store.since(since, deviceId, want));
        for (CabinEvent e : events.recent(deviceId, want, 0, since, AUTOMATION_EVENT_TYPES)) {
            merged.add(fromEvent(e));
        }
        merged.sort(Comparator.comparing(AlertHistoryEntry::occurredAt).reversed());
        int from = Math.min(Math.max(offset, 0), merged.size());
        int to = Math.min(from + Math.max(limit, 0), merged.size());
        return merged.subList(from, to);
    }

    private AlertHistoryEntry fromEvent(CabinEvent e) {
        Object ruleId = e.payload() == null ? null : e.payload().get("ruleId");
        Object see = e.payload() == null ? null : e.payload().get("see");
        String rule = ruleId == null ? null : ruleId.toString();
        return new AlertHistoryEntry("e:" + e.eventId(), e.timestamp(), AlertKeys.automation(rule, e.sourceDeviceId()),
            "automation", e.sourceDeviceId(), null, rule, e.severity(), "RAISED", null, null,
            see == null ? null : see.toString());
    }
}
