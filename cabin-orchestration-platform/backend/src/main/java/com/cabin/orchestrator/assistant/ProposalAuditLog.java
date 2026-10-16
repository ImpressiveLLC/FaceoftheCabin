package com.cabin.orchestrator.assistant;

import java.time.Instant;
import java.util.List;

/**
 * The shadow-mode record of every propose call (HO-2026-10-16 step 5): request id, model tag, raw model output,
 * validation result, the resolver's decisions and reasons, and the proposal returned. Free text is redacted
 * before it gets here ({@link ProposalAuditLog#redact}).
 */
public interface ProposalAuditLog {

    record Decision(String role, String outcome, String entityId, List<String> reasons) {}

    record Entry(String requestId, Instant at, String principal, String householdRole, String model,
                 String input, String rawModelOutput, long modelLatencyMillis, String validation,
                 String intent, String status, String code, List<Decision> decisions,
                 String proposalId, String planHash, long totalLatencyMillis) {}

    void record(Entry entry);

    /**
     * Free text can hold a pasted secret. Drop anything that looks like one before it is stored:
     * "password/token/secret/api key" followed by a value, and any long unbroken token-like string.
     */
    static String redact(String text) {
        if (text == null) return null;
        String out = text.replaceAll("(?i)(pass(word|wd)?|token|secret|api[ _-]?key|credential)s?\\s*(is|:|=)?\\s*\\S+", "$1 [redacted]");
        out = out.replaceAll("[A-Za-z0-9+/_=-]{24,}", "[redacted]");
        return out.replaceAll("[\\p{Cntrl}&&[^\\n]]+", " ");
    }
}
