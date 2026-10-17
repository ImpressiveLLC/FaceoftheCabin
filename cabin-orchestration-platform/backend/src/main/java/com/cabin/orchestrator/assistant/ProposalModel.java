package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.helpdesk.TinyHelpdeskAnswer;

import java.time.Instant;
import java.util.List;

/** Response shapes of {@code POST /api/helpdesk/propose} (HO-2026-10-16 step 3). Nothing here executes anything. */
public final class ProposalModel {

    private ProposalModel() {}

    public enum Status {
        /** answer_question: handed to the existing Ask path; {@code answer} carries its citations. */
        ANSWER,
        /** The model needs more from the person; {@code message} is the question. */
        CLARIFY,
        /** express_need: the need was recognised and named, but no options are offered yet (no executor, no concept map). */
        NEED,
        /** A read of one or more devices resolved to exactly one target each. Nothing to confirm. */
        TARGET,
        /** A mutation or automation resolved completely; {@code proposal} binds a later confirmation. */
        PROPOSAL,
        /** Fail closed: see {@code code}, and {@code refusals} when the resolver said why. */
        REFUSED
    }

    /**
     * One resolved part of a plan. {@code role} is ACTION, TRIGGER or CONDITION. A time trigger names no device:
     * its {@code entityId} is null, {@code action} is TIME_ONCE or TIME_RECURRING and {@code detail} holds the
     * schedule. {@code detail} also carries a state trigger's "for N seconds".
     */
    public record Step(String role, String entityId, String name, String site, String action, String attribute,
                       String operator, String value, String unit, int delaySeconds, String detail) {}

    /**
     * What a later confirmation must be bound to (D27 R-OC-7, Q-OC-10): the principal, the exact resolved steps
     * (through {@code planHash}), the inventory version they were resolved against, and an expiry.
     * {@code requiresConfirmation} is always true. There is no executor behind this route.
     */
    public record Plan(String proposalId, String planHash, Instant expiresAt, boolean requiresConfirmation,
                       String inventoryVersion, String principal) {}

    public record Candidate(String entityId, String name, String site) {}

    /** The resolver's typed refusal for one target of the request. */
    public record Refusal(String role, String outcome, List<Candidate> candidates, List<String> reasons) {}

    public record Response(String requestId, Status status, String intent, String code, String message,
                           Plan proposal, List<Step> steps, List<Refusal> refusals, TinyHelpdeskAnswer answer) {}
}
