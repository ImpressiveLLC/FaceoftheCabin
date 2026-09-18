package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.AcknowledgeAlertRequest;
import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.alerts.ActiveAlertsSnapshot;
import com.cabin.orchestrator.alerts.AlertAcknowledgment;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public AlertController(ActiveAlertService activeAlerts, AutomationRuleService automationRules,
                            JdbcAlertAcknowledgmentStore acknowledgments) {
        this.activeAlerts = activeAlerts;
        this.automationRules = automationRules;
        this.acknowledgments = acknowledgments;
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
        acknowledgments.save(new AlertAcknowledgment(
            body.alertKey(), body.mode(), body.snoozedUntil(), actorEmail(request), Instant.now()));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** Un-ignore/un-snooze -- brings the alert back to "new" immediately instead of waiting out a snooze timer. Google-token gated, same reasoning as acknowledge() above. */
    @DeleteMapping("/acknowledgments/{alertKey}")
    public ResponseEntity<?> clearAcknowledgment(@PathVariable String alertKey) {
        acknowledgments.clear(alertKey);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private String actorEmail(HttpServletRequest request) {
        Object email = request.getAttribute(GoogleAuthInterceptor.REQUEST_ATTR_EMAIL);
        return email != null ? email.toString() : "unknown";
    }
}
