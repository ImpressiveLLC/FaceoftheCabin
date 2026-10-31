package com.cabin.orchestrator.assistant;

import com.cabin.orchestrator.assistant.IntentSlotContract.ContractViolation;
import com.cabin.orchestrator.assistant.IntentSlotContract.ParsedIntent;
import com.cabin.orchestrator.assistant.ProposalModel.Candidate;
import com.cabin.orchestrator.assistant.ProposalModel.Plan;
import com.cabin.orchestrator.assistant.ProposalModel.Refusal;
import com.cabin.orchestrator.assistant.ProposalModel.Response;
import com.cabin.orchestrator.assistant.ProposalModel.Status;
import com.cabin.orchestrator.assistant.ProposalModel.Step;
import com.cabin.orchestrator.assistant.ResolverModel.ActionKind;
import com.cabin.orchestrator.assistant.ResolverModel.ActionRequest;
import com.cabin.orchestrator.assistant.ResolverModel.Outcome;
import com.cabin.orchestrator.assistant.ResolverModel.Resolution;
import com.cabin.orchestrator.assistant.ResolverModel.SemanticTarget;
import com.cabin.orchestrator.helpdesk.TinyHelpdeskService;
import com.cabin.orchestrator.security.HouseholdRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * HO-2026-10-16 step 3 (W-75 route): language in, reviewable proposal out. The model classifies the request
 * (IntentSlotContract), {@link EntityResolver} decides which device it means, and the result is a
 * {@link ProposalModel.Response}. It fails closed on malformed output, an unavailable model, an
 * {@code unsupported} intent or any resolver refusal.
 *
 * <p><b>Nothing here executes.</b> This class depends on no executor, protocol adapter, Home Assistant client
 * or workflow engine; AssistantProposalNoExecutionTest proves it by dependency and by behaviour. A proposal is
 * data bound to a principal, the resolved steps, the inventory version and an expiry (D27 R-OC-7, Q-OC-10);
 * whether and how it ever runs is a later, separately gated step.
 */
@Service
public class IntentProposalService {

    private final IntentChatClient chatClient;
    private final IntentSlotContract contract;
    private final AssistantInventoryService inventoryService;
    private final EntityResolver resolver;
    private final TinyHelpdeskService helpdesk;
    private final ProposalAuditLog auditLog;
    private final Clock clock;
    private final boolean enabled;
    private final String model;
    private final Duration proposalTtl;

    @Autowired
    public IntentProposalService(IntentChatClient chatClient, IntentSlotContract contract,
                                 AssistantInventoryService inventoryService, EntityResolver resolver,
                                 TinyHelpdeskService helpdesk, ProposalAuditLog auditLog,
                                 @Value("${cabin.ask.intent.enabled:false}") boolean enabled,
                                 @Value("${cabin.ask.intent.model:cabin-intent-slot-prompt-r1}") String model,
                                 @Value("${cabin.ask.intent.proposal-ttl-seconds:300}") long proposalTtlSeconds) {
        this(chatClient, contract, inventoryService, resolver, helpdesk, auditLog, Clock.systemUTC(),
            enabled, model, Duration.ofSeconds(proposalTtlSeconds));
    }

    IntentProposalService(IntentChatClient chatClient, IntentSlotContract contract,
                          AssistantInventoryService inventoryService, EntityResolver resolver,
                          TinyHelpdeskService helpdesk, ProposalAuditLog auditLog, Clock clock,
                          boolean enabled, String model, Duration proposalTtl) {
        this.chatClient = chatClient;
        this.contract = contract;
        this.inventoryService = inventoryService;
        this.resolver = resolver;
        this.helpdesk = helpdesk;
        this.auditLog = auditLog;
        this.clock = clock;
        this.enabled = enabled;
        this.model = model;
        this.proposalTtl = proposalTtl;
    }

    public boolean enabled() {
        return enabled;
    }

    public Response propose(String text, HouseholdRole role, String principal) {
        long started = System.nanoTime();
        String requestId = UUID.randomUUID().toString();
        List<ProposalAuditLog.Decision> decisions = new ArrayList<>();
        String rawOutput = null;
        long modelMillis = 0;
        String validation = "NOT_RUN";

        Optional<IntentChatClient.ChatResult> chat = chatClient.chat(model, contract.systemPrompt(), contract.requestContent(text), contract.schema());
        Response response;
        ParsedIntent parsed = null;
        if (chat.isEmpty()) {
            validation = "NO_MODEL_OUTPUT";
            response = refused(requestId, null, "MODEL_UNAVAILABLE", "The language model did not answer, so nothing was proposed.");
        } else {
            rawOutput = chat.get().content();
            modelMillis = chat.get().latencyMillis();
            try {
                parsed = contract.validate(rawOutput);
                validation = "OK";
                response = handle(requestId, text, parsed, role, principal, decisions);
            } catch (ContractViolation e) {
                validation = "INVALID: " + e.getMessage();
                response = refused(requestId, null, "MALFORMED_MODEL_OUTPUT",
                    "I could not turn that into a safe, structured request, so nothing was proposed.");
            }
        }

        auditLog.record(new ProposalAuditLog.Entry(requestId, clock.instant(), principal, role == null ? null : role.name(),
            model, ProposalAuditLog.redact(text), ProposalAuditLog.redact(rawOutput), modelMillis, validation,
            response.intent(), response.status().name(), response.code(), List.copyOf(decisions),
            response.proposal() == null ? null : response.proposal().proposalId(),
            response.proposal() == null ? null : response.proposal().planHash(),
            (System.nanoTime() - started) / 1_000_000));
        return response;
    }

    private Response handle(String requestId, String text, ParsedIntent parsed, HouseholdRole role, String principal,
                            List<ProposalAuditLog.Decision> decisions) {
        String intent = parsed.intent();
        switch (intent) {
            case "answer_question":
                return new Response(requestId, Status.ANSWER, intent, null, null, null, List.of(), List.of(),
                    helpdesk.ask(text, role));
            case "clarify":
                return new Response(requestId, Status.CLARIFY, intent, null, parsed.clarification(), null, List.of(), List.of(), null);
            case "express_need":
                // The need is named and nothing more: choosing options is the ontology walk (W-66/W-68/W-75), not the model.
                return new Response(requestId, Status.NEED, intent, null,
                    "I understood that as the need “" + parsed.slots().get("concept").replace('_', ' ')
                        + "”. I can't suggest actions for it yet.", null, List.of(), List.of(), null);
            case "unsupported":
                return refused(requestId, intent, "UNSUPPORTED", "I can't help with that request.");
            default:
                break;
        }

        // The model decides answer-versus-act and is fallible. Whatever it said, a question-shaped request never becomes a
        // proposal: ask instead (a read of a device's state, get_device_state, is not an action and is left alone).
        if (!"get_device_state".equals(intent) && QuestionShape.isQuestion(text)) {
            decisions.add(new ProposalAuditLog.Decision("request", "QUESTION_SHAPED", null,
                List.of("question-shaped request: the model chose " + intent + ", so no proposal was made")));
            return new Response(requestId, Status.CLARIFY, intent, "QUESTION_SHAPED",
                "That sounded like a question. If you want me to do it, say so plainly (for example “turn on the porch light”); "
                    + "if you are asking how it works, ask again and I will answer.", null, List.of(), List.of(), null);
        }

        List<Part> parts = partsOf(parsed);
        if (parts == null) {
            return refused(requestId, intent, "MALFORMED_MODEL_OUTPUT", "I could not turn that into a safe, structured request, so nothing was proposed.");
        }
        AssistantInventory inventory = inventoryService.snapshot(role);
        List<Step> steps = new ArrayList<>();
        List<Refusal> refusals = new ArrayList<>();
        for (Part part : parts) {
            if (part.target() == null) {
                // A time trigger names no device, so there is nothing to resolve.
                steps.add(new Step(part.role(), null, null, null, part.kind(), null, null, null, null, 0, part.detail()));
                decisions.add(new ProposalAuditLog.Decision(part.role(), "NOT_APPLICABLE", null, List.of("time trigger names no device")));
                continue;
            }
            Resolution r = resolver.resolve(part.target(), part.action(), inventory, role);
            decisions.add(new ProposalAuditLog.Decision(part.role(), r.outcome().name(),
                r.target() == null ? null : r.target().entityId(), r.reasons()));
            if (r.resolved()) {
                var a = r.action();
                boolean set = a.kind() == ActionKind.SET_ATTRIBUTE;
                steps.add(new Step(part.role(), r.target().entityId(), r.target().name(), r.target().site(), a.kind().name(),
                    set ? a.attribute() : part.attribute(), part.operator(), set ? a.canonicalValue() : part.value(),
                    set ? a.canonicalUnit() : part.unit(), part.delaySeconds(), part.detail()));
            } else {
                refusals.add(new Refusal(part.role(), r.outcome().name(),
                    r.candidates().stream().map(c -> new Candidate(c.entityId(), c.name(), c.site())).toList(), r.reasons()));
            }
        }
        if (!refusals.isEmpty()) {
            return new Response(requestId, Status.REFUSED, intent, "RESOLUTION_FAILED", refusalMessage(refusals.get(0)),
                null, List.copyOf(steps), List.copyOf(refusals), null);
        }
        if ("get_device_state".equals(intent)) {
            return new Response(requestId, Status.TARGET, intent, null, null, null, List.copyOf(steps), List.of(), null);
        }
        Instant expires = clock.instant().plus(proposalTtl);
        Plan plan = new Plan(UUID.randomUUID().toString(), planHash(principal, inventory.version(), steps), expires, true,
            inventory.version(), principal);
        return new Response(requestId, Status.PROPOSAL, intent, null, null, plan, List.copyOf(steps), List.of(), null);
    }

    // ── turning the validated intent into resolver inputs ────────────────

    private record Part(String role, SemanticTarget target, ActionRequest action, String attribute, String operator,
                        String value, String unit, int delaySeconds, String detail, String kind) {
        Part(String role, SemanticTarget target, ActionRequest action, String attribute, String operator,
             String value, String unit, int delaySeconds) {
            this(role, target, action, attribute, operator, value, unit, delaySeconds, null, null);
        }
    }

    private static List<Part> partsOf(ParsedIntent parsed) {
        Map<String, String> s = parsed.slots();
        SemanticTarget target = new SemanticTarget(s.get("device_name"), s.get("location"), s.get("site"));
        switch (parsed.intent()) {
            case "get_device_state":
                return List.of(new Part("ACTION", target, ActionRequest.read(), null, null, null, null, 0));
            case "set_device_state":
                return List.of(new Part("ACTION", target, ActionRequest.state(s.get("state")), null, null, null, null, 0));
            case "set_device_attribute":
                return List.of(new Part("ACTION", target,
                    ActionRequest.set(s.get("attribute_type"), s.get("attribute_value"), s.get("unit")), null, null, null, null, 0));
            case "propose_automation":
                return automationParts(parsed.automation());
            default:
                return null;
        }
    }

    private static List<Part> automationParts(JsonNode automation) {
        if (automation == null) return null;
        List<Part> parts = new ArrayList<>();
        parts.add(triggerPart(automation.get("trigger")));
        for (JsonNode condition : automation.path("conditions")) parts.add(watchPart("CONDITION", condition));
        for (JsonNode action : automation.path("actions")) {
            SemanticTarget target = targetOf(action.get("target"));
            String operation = action.path("operation").asText();
            ActionRequest request = "set".equals(operation)
                ? ActionRequest.set(text(action.get("attribute")), text(action.get("value")), text(action.get("unit")))
                : ActionRequest.state(operation);
            parts.add(new Part("ACTION", target, request, text(action.get("attribute")), null, text(action.get("value")),
                text(action.get("unit")), action.path("delay_seconds").asInt(0)));
        }
        return parts;
    }

    /** The trigger shapes of the POC2 contract: a device predicate, one time, a recurring time, or a state change. */
    private static Part triggerPart(JsonNode trigger) {
        String kind = text(trigger.get("kind"));
        if (kind == null) return watchPart("TRIGGER", trigger);
        switch (kind) {
            case "time_once":
                return new Part("TRIGGER", null, null, null, null, null, null, 0, "at " + text(trigger.get("at")), "TIME_ONCE");
            case "time_recurring":
                return new Part("TRIGGER", null, null, null, null, null, null, 0,
                    "from " + text(trigger.get("start_time")) + ", repeat " + text(trigger.get("rrule")), "TIME_RECURRING");
            default: { // state_changes_to
                JsonNode seconds = trigger.get("for_seconds");
                return new Part("TRIGGER", targetOf(trigger.get("target")), ActionRequest.read(), text(trigger.get("attribute")),
                    "changes_to", text(trigger.get("value")), null, 0,
                    seconds == null || seconds.isNull() ? null : "for " + seconds.asInt() + " seconds", null);
            }
        }
    }

    /** A trigger or condition only has to name one readable device; its value test is carried through unchanged. */
    private static Part watchPart(String role, JsonNode node) {
        return new Part(role, targetOf(node.get("target")), ActionRequest.read(), text(node.get("attribute")),
            text(node.get("operator")), text(node.get("value")), text(node.get("unit")), 0);
    }

    private static SemanticTarget targetOf(JsonNode target) {
        return new SemanticTarget(text(target.get("device_name")), text(target.get("location")), text(target.get("site")));
    }

    private static String text(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    // ── results ──────────────────────────────────────────────────────────

    private static Response refused(String requestId, String intent, String code, String message) {
        return new Response(requestId, Status.REFUSED, intent, code, message, null, List.of(), List.of(), null);
    }

    private static String refusalMessage(Refusal refusal) {
        String names = refusal.candidates().stream().map(Candidate::name).toList().toString();
        return switch (Outcome.valueOf(refusal.outcome())) {
            case AMBIGUOUS -> "More than one device fits: " + names + ". Which one do you mean?";
            case NOT_FOUND -> "I couldn't find a device that fits that.";
            case WRONG_SITE -> "That device isn't at that location. It is at: " + names + ".";
            case UNSUPPORTED_CAPABILITY -> "That device can't do that.";
            case STALE -> "I don't have fresh enough information about that device to act on it.";
            case OFFLINE -> "That device is offline or disabled.";
            case UNAUTHORIZED -> "You aren't allowed to do that.";
            case INVALID_VALUE -> "I need a clearer value or unit for that: " + String.join("; ", refusal.reasons()) + ".";
            case GUARDED -> "That action is not available from Ask.";
            default -> "I could not resolve that request.";
        };
    }

    /** SHA-256 over the principal, the inventory version and every resolved step, in order. */
    static String planHash(String principal, String inventoryVersion, List<Step> steps) {
        StringBuilder canonical = new StringBuilder(String.valueOf(principal)).append('\n').append(inventoryVersion).append('\n');
        for (Step s : steps) {
            canonical.append(String.join("\u001f", s.role(), s.entityId(), s.action(), String.valueOf(s.attribute()),
                String.valueOf(s.operator()), String.valueOf(s.value()), String.valueOf(s.unit()),
                Integer.toString(s.delaySeconds()), String.valueOf(s.detail()))).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JRE", e);
        }
    }
}
