package com.cabin.orchestrator.assistant;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Input and output types of {@link EntityResolver} (HO-2026-10-16 step 2,
 * W-75). The input mirrors the semantic slots of the intent/slot contract
 * (docs/ai-assistant/training/intent-slot/intent-slot.schema.json): the model
 * names things in words, never as entity ids (D27 R-OC-5).
 */
public final class ResolverModel {

    private ResolverModel() {}

    /** Semantic target slots: device_name, location (room), site. Any may be null. */
    public record SemanticTarget(String deviceName, String location, String site) {}

    public enum ActionKind {
        READ, TURN_ON, TURN_OFF, OPEN, CLOSE, LOCK, UNLOCK, SET_ATTRIBUTE,
        /** A state word the resolver does not recognise, including any negation. Always refused. */
        UNRECOGNIZED
    }

    /**
     * What the request asks to do. {@code value} and {@code unit} are only used by SET_ATTRIBUTE.
     * {@code rawState} keeps the original wording of an unrecognised state for the refusal reason.
     */
    public record ActionRequest(ActionKind kind, String attribute, String value, String unit, String rawState) {

        private static final Set<String> NEGATIONS = Set.of("not", "no", "never", "dont", "don't", "cannot", "cant", "can't", "without", "except");

        public static ActionRequest read() {
            return new ActionRequest(ActionKind.READ, null, null, null, null);
        }

        public static ActionRequest set(String attribute, String value, String unit) {
            return new ActionRequest(ActionKind.SET_ATTRIBUTE, attribute, value, unit, null);
        }

        /**
         * Maps the contract's {@code state} slot. Only the bare words below are accepted; anything else
         * (a negation, a phrase, an unknown word) becomes UNRECOGNIZED and is refused rather than guessed at.
         */
        public static ActionRequest state(String state) {
            String word = state == null ? "" : state.trim().toLowerCase(Locale.ROOT);
            ActionKind kind = switch (word) {
                case "on" -> ActionKind.TURN_ON;
                case "off" -> ActionKind.TURN_OFF;
                case "open", "opened" -> ActionKind.OPEN;
                case "close", "closed", "shut" -> ActionKind.CLOSE;
                case "lock", "locked" -> ActionKind.LOCK;
                case "unlock", "unlocked" -> ActionKind.UNLOCK;
                default -> ActionKind.UNRECOGNIZED;
            };
            return new ActionRequest(kind, null, null, null, state);
        }

        boolean negated() {
            if (rawState == null) return false;
            for (String token : rawState.toLowerCase(Locale.ROOT).split("[^a-z']+")) {
                if (NEGATIONS.contains(token)) return true;
            }
            return false;
        }

        boolean mutates() {
            return kind != ActionKind.READ;
        }
    }

    /** The action after validation: the value is in one canonical unit (temperature in degrees Celsius). */
    public record ResolvedAction(ActionKind kind, String attribute, String canonicalValue, String canonicalUnit,
                                 String requestedValue, String requestedUnit) {}

    public enum Outcome {
        RESOLVED,
        /** More than one device fits; {@code candidates} lists them. */
        AMBIGUOUS,
        NOT_FOUND,
        /** The device exists, but not at the site the request named. */
        WRONG_SITE,
        /** The device lacks the capability the action needs. */
        UNSUPPORTED_CAPABILITY,
        /** The inventory or the device's last observation is too old to act on. */
        STALE,
        OFFLINE,
        UNAUTHORIZED,
        /** The value, unit or state word is missing, unsafe or not understood. */
        INVALID_VALUE,
        /** A control guard refuses this direction for Ask (D27 Q-OC-2): valve open, lock unlock. */
        GUARDED
    }

    /**
     * Exactly one of: a RESOLVED target and action, or a typed refusal. {@code reasons} always explains the
     * decision, and is logged. {@code inventoryVersion} is the version the decision was made against, so a
     * later confirmation can be bound to it (D27 Q-OC-10).
     */
    public record Resolution(Outcome outcome, AssistantInventory.Entry target,
                             List<AssistantInventory.Entry> candidates, ResolvedAction action,
                             String inventoryVersion, List<String> reasons) {

        public boolean resolved() {
            return outcome == Outcome.RESOLVED;
        }
    }
}
