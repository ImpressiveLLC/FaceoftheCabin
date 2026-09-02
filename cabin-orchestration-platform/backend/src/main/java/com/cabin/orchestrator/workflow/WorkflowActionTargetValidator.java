package com.cabin.orchestrator.workflow;

import com.cabin.orchestrator.devices.DeviceRegistry;
import com.cabin.orchestrator.devices.model.DeviceCapability;
import com.cabin.orchestrator.devices.model.DeviceDescriptor;
import com.cabin.orchestrator.workflow.model.ActionVocabularyEntry;
import com.cabin.orchestrator.workflow.model.WorkflowAction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The real targetDeviceId check this app never had -- nothing before this
 * stopped a workflow action from being saved pointing at a device that
 * doesn't exist, lacks the capability the action needs, or is disabled/
 * unassigned and could never actually receive the command. Shared by
 * RulesController's create/activate validation and workflow-health
 * computation so both read the exact same rule, never a second, divergent
 * check.
 *
 * Gates on allowsActiveUse() (ASSIGNED only), not the looser isInScope()
 * (AVAILABLE || ASSIGNED) -- DeviceRegistry.sendCommand()/.activeFetch()
 * already gate dispatch on allowsActiveUse(), so validating against the
 * looser check here would let a workflow pass creation-time validation
 * while still failing at actual dispatch time.
 */
@Service
public class WorkflowActionTargetValidator {

    private final DeviceRegistry registry;
    private final JdbcWorkflowVocabularyStore vocabularyStore;

    public WorkflowActionTargetValidator(DeviceRegistry registry, JdbcWorkflowVocabularyStore vocabularyStore) {
        this.registry = registry;
        this.vocabularyStore = vocabularyStore;
    }

    /** Null if every action's target is valid; otherwise a human-readable reason for the first violation found. */
    public String validate(List<WorkflowAction> actions) {
        Map<String, ActionVocabularyEntry> vocabularyById = vocabularyStore.loadSupportedActions().stream()
            .collect(Collectors.toMap(ActionVocabularyEntry::id, a -> a));
        for (WorkflowAction action : actions) {
            ActionVocabularyEntry vocab = vocabularyById.get(action.actionDefinitionId());
            // Unsupported/candidate action ids and non-target actions (log/notify)
            // have nothing to validate here -- WorkflowRuleService.executeAction()
            // is the real enforcement point for "does this action id do anything."
            if (vocab == null || !vocab.needsTarget()) continue;

            String targetDeviceId = action.targetDeviceId();
            if (targetDeviceId == null || targetDeviceId.isBlank()) {
                return action.actionDefinitionId() + " requires a target device";
            }
            Optional<DeviceDescriptor> descriptor = registry.descriptor(targetDeviceId);
            if (descriptor.isEmpty()) {
                return "Target device " + targetDeviceId + " does not exist";
            }
            if (vocab.requiresCapability() != null) {
                DeviceCapability required;
                try {
                    required = DeviceCapability.valueOf(vocab.requiresCapability());
                } catch (IllegalArgumentException e) {
                    return "Unknown required capability " + vocab.requiresCapability() + " for " + action.actionDefinitionId();
                }
                if (!descriptor.get().capabilities().contains(required)) {
                    return "Target device " + targetDeviceId + " does not have the " + required + " capability required by " + action.actionDefinitionId();
                }
            }
            if (!registry.lifecycleState(targetDeviceId).allowsActiveUse()) {
                return "Target device " + targetDeviceId + " is not assigned/active, so " + action.actionDefinitionId() + " could never actually run against it";
            }
        }
        return null;
    }
}
