package com.cabin.orchestrator.workflow.model;

/**
 * One action's real-world health, computed by
 * WorkflowActionTargetValidator.health() -- see that method's own doc for
 * why every boolean here is derived from the same checks validate() uses,
 * not a second, divergent definition of "valid."
 *
 * targetDeviceId is null for an action that doesn't need one (log/notify)
 * or one whose actionDefinitionId isn't in the current vocabulary --
 * deviceExists/hasCapability/activeUseAllowed/online are all vacuously
 * true in that case (nothing to check), matching validate()'s own
 * "continue" for the same condition.
 *
 * When a target device doesn't exist, hasCapability/activeUseAllowed/
 * online are all false rather than "not applicable" -- there is nothing
 * else to check against, and false keeps every downstream reader (this
 * class's own healthy(), the frontend badge) from having to special-case
 * "true, but only because there was nothing to check."
 */
public record WorkflowActionHealthEntry(
    String actionId,
    String actionDefinitionId,
    String targetDeviceId,
    boolean deviceExists,
    boolean hasCapability,
    boolean activeUseAllowed,
    boolean online
) {
    /** Would this action actually run if the workflow fired right now (ignoring live reachability)? */
    public boolean healthy() {
        return deviceExists && hasCapability && activeUseAllowed;
    }
}
