package com.cabin.orchestrator.automation;

/** Read-only description of a rule that the backend actually evaluates. */
public record AutomationRuleStatus(
    String ruleId,
    String name,
    String trigger,
    String action,
    String severity,
    boolean enabled,
    String owner,
    String configurationMode,
    String configurationKey,
    boolean editable
) {}
