package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.AcknowledgeAlertRequest;
import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.alerts.ActiveAlertsSnapshot;
import com.cabin.orchestrator.alerts.AlertAcknowledgment;
import com.cabin.orchestrator.alerts.AlertHistoryEntry;
import com.cabin.orchestrator.alerts.AlertHistoryService;
import com.cabin.orchestrator.alerts.JdbcAlertAcknowledgmentStore;
import com.cabin.orchestrator.automation.AutomationRuleService;
import com.cabin.orchestrator.automation.AutomationRuleStatus;
import com.cabin.orchestrator.security.GoogleAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin
public class AlertController {

    private static final Set<String> VALID_MODES = Set.of("IGNORED", "SNOOZED");

    private final ActiveAlertService activeAlerts;
    private final AutomationRuleService automationRules;
    private final JdbcAlertAcknowledgmentStore acknowledgments;
    private final AlertHistoryService history;

    public AlertController(ActiveAlertService activeAlerts, AutomationRuleService automationRules,
                            JdbcAlertAcknowledgmentStore acknowledgments, AlertHistoryService history) {
        this.activeAlerts = activeAlerts;
        this.automationRules = automationRules;
        this.acknowledgments = acknowledgments;
        this.history = history;
    }

    /** Current conditions only; historical automation decisions remain under /api/events. */
    @GetMapping("/active")
    public ActiveAlertsSnapshot active() {
        return activeAlerts.snapshot();
    }

    /** Rules the cabin backend actually evaluates, with their real configuration ownership. */
    @GetMapping("/rules")
    public List<AutomationRuleStatus> rules() {
        return automationRules.ruleStatuses();
    }

    /**
     * Currently-suppressed alert keys (IGNORED, or SNOOZED and not yet
     * expired). Read stays open, matching every other GET under this
     * prefix (see GoogleAuthInterceptor's isAlertsRead carve-out) --
     * suppression state doesn't reveal occupancy any more than the alerts
     * themselves do. Only the write endpoints below are gated: silencing a
     * leak/CO/security alert is a real action, not a glanceable status
     * read, unlike everything else on this controller.
     */
    @GetMapping("/acknowledgments")
    public List<AlertAcknowledgment> acknowledgments() {
        return acknowledgments.activeAcknowledgments();
    }

    /** Snooze or ignore an alert. Google-token gated (see WebConfig) -- an anonymous caller must never be able to silence a safety-relevant alert. */
    @PostMapping("/acknowledgments")
    public ResponseEntity<?> acknowledge(@RequestBody AcknowledgeAlertRequest body, HttpServletRequest request) {
        if (body.alertKey() == null || body.alertKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "alertKey is required"));
        }
        if (body.mode() == null || !VALID_MODES.contains(body.mode())) {
            return ResponseEntity.badRequest().body(Map.of("error", "mode must be one of " + VALID_MODES));
        }
        if ("SNOOZED".equals(body.mode()) && body.snoozedUntil() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "snoozedUntil is required when mode is SNOOZED"));
        }
        if ("IGNORED".equals(body.mode()) && body.snoozedUntil() != null) {
            return ResponseEntity.badRequest().body(Map.of("error", "snoozedUntil must be omitted when mode is IGNORED"));
        }
        String actor = actorEmail(request);
        Instant now = Instant.now();
        acknowledgments.save(new AlertAcknowledgment(body.alertKey(), body.mode(), body.snoozedUntil(), actor, now));
        history.recordAcknowledgmentAction(body.alertKey(), body.mode(), actor, body.snoozedUntil(), now);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Un-ignore/un-snooze -- brings the alert back to "new" immediately instead of waiting out a snooze timer. Google-token gated, same reasoning as acknowledge() above. */
    @DeleteMapping("/acknowledgments/{alertKey}")
    public ResponseEntity<?> clearAcknowledgment(@PathVariable String alertKey, HttpServletRequest request) {
        acknowledgments.clear(alertKey);
        history.recordAcknowledgmentAction(alertKey, "UN_IGNORED", actorEmail(request), null, Instant.now());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /**
     * W-56 -- the reviewable alert history: device-condition raised/resolved,
     * every ignore/snooze/un-ignore/expiry, and the automation alerts already
     * in cabin_event, newest first. Deliberately NOT open like the other GETs
     * under this prefix (GoogleAuthInterceptor exempts this exact path, the
     * guest-token check refuses it, and DemoAccessPolicy denies it): it lists
     * when doors opened and motion fired, and who silenced what, which is
     * exactly what the /api/events gate exists to protect.
     */
    @GetMapping("/history")
    public List<AlertHistoryEntry> history(@RequestParam(defaultValue = "7d") String window,
                                            @RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(defaultValue = "0") int offset,
                                            @RequestParam(required = false) String deviceId) {
        Instant since = Instant.now().minus(parseWindow(window));
        return history.history(since, deviceId, Math.min(Math.max(limit, 1), 500), Math.max(offset, 0));
    }

    private Duration parseWindow(String window) {
        try {
            char unit = window.charAt(window.length() - 1);
            long value = Long.parseLong(window.substring(0, window.length() - 1));
            Duration d = switch (unit) {
                case 'h' -> Duration.ofHours(value);
                case 'd' -> Duration.ofDays(value);
                default -> Duration.ofDays(7);
            };
            Duration max = Duration.ofDays(90);
            return d.compareTo(max) > 0 ? max : d;
        } catch (Exception e) {
            return Duration.ofDays(7);
        }
    }

    private String actorEmail(HttpServletRequest request) {
        Object email = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        return email != null ? email.toString() : "unknown";
    }
}
