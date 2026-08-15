package com.cabin.orchestrator.api;

import com.cabin.orchestrator.alerts.ActiveAlertService;
import com.cabin.orchestrator.alerts.ActiveAlertsSnapshot;
import com.cabin.orchestrator.automation.AutomationRuleService;
import com.cabin.orchestrator.automation.AutomationRuleStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin
public class AlertController {

    private final ActiveAlertService activeAlerts;
    private final AutomationRuleService automationRules;

    public AlertController(ActiveAlertService activeAlerts, AutomationRuleService automationRules) {
        this.activeAlerts = activeAlerts;
        this.automationRules = automationRules;
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
}
