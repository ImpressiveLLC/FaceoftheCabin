package com.cabin.orchestrator.opportunities;

/**
 * OPEN -> ACKNOWLEDGED -> RESOLVED, admin-driven only -- the analytics job
 * never transitions a row itself (see OptimizationAnalyticsService), so an
 * ACKNOWLEDGED or RESOLVED opportunity stays exactly where a person put it
 * even while the underlying condition is still ongoing. There is no
 * OPEN-again transition via the API: a resolved opportunity that recurs
 * gets a brand-new row (fresh id, fresh detectedAt) the next time the job
 * runs and finds no existing OPEN/ACKNOWLEDGED row for that device+type --
 * matching the intent of "resolved" meaning "this specific instance is
 * handled," not "never flag this device again."
 */
public enum OpportunityStatus {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
}
