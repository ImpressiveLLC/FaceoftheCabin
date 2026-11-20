# express_need `requires_confirmation`: which side changes (proposal, 2026-11-20)

Status: **proposal only; nothing changed.** This note was requested by Cowork on #244 (7030937825). It records a mismatch already on main at `3e441f5b`. It does not edit `system.txt`, `CLAUDE.md`, any contract, schema or training row. Training NOT_RUN.

## The mismatch

The **executable contracts** require `requires_confirmation: true` for `express_need`. Four places agree:

- **Java:** [IntentSlotContract](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/assistant/IntentSlotContract.java) lists `express_need` in `MUTATIONS` (line 37) and rejects a mutation without confirmation (line 89, "mutations require confirmation").
- **Python:** [`contract.py`](../training/intent-slot/contract.py) `validate` raises "Mutations require confirmation" for the same four intents (lines 18-22). Its builder sets `requires_confirmation` true for them (lines 79-81).
- **Prompt:** both `system.txt` copies, the backend resource and the training copy, say "Needs require confirmation; never choose a device or action from a need alone" (line 26).
- **Data:** every authored `express_need` candidate (S2 batch 4, #531) and the r10/r11 prompt examples use `true`. The batch-4 note says outright that it does not adopt CLAUDE.md's older example.

The **specification** in [`CLAUDE.md`](../../../CLAUDE.md), "POC2 handoff: `express_need`", shows `"requires_confirmation": false` in its example envelope. It labels that envelope "Proposed extension ... (not valid under r1 yet)". The section is marked "Specification only; implementation pending" and was last edited 2026-11-15.

## What `true` means here

`true` on an `express_need` row is the current contracts' and data's convention. It does **not** mean the model selected an action, and it gives no authority to execute anything. Recognizing a need has no side effect. Any action that follows, such as offering a heater, is a separate proposal that needs the person's confirmation whatever the model field says. A safe response to a need can also be an explanation or a clarifying question rather than an action.

CLAUDE.md already says this in prose under its example: "Here `false` means recognizing a need has no side effect. A subsequent action proposal requires confirmation regardless of that model field." So the mismatch is the meaning given to the field, not the safety rule. CLAUDE.md uses `false` for "no side effect from recognition", while the package uses `true` for "a resulting action needs confirmation". The safety rule itself is the same in both.

## Ruling (Cowork, #244 7032742877) and proposal

**Ruled:** `express_need` keeps `requires_confirmation: true` in the contracts, both validators, both `system.txt` copies and the data, per the S2 convention. Nothing in the package changes.

**CLAUDE.md wording changes, separately.** Cowork or Nate makes that edit, not this PR. It should either state that the data convention is `true` and that `true` marks only that a resulting action needs confirmation, or change the example to match the package. Either way, the example JSON and the adjacent "Here `false` means ..." paragraph must change together; changing one JSON value alone would leave contradictory guidance. `AGENTS.md` is generated from CLAUDE.md, so it must be regenerated with `fed-regen.sh` in the same change, and `fed-check.sh` should then report no drift.

**What this note does not claim:**

- **Validators.** Relaxing them to accept `false` would not by itself invalidate the existing `true` rows. Keeping `true` is alignment with the current contracts and data, not a forced choice.
- **The adjacent rule.** Nothing here weakens the rule that a subsequent action needs the person's confirmation.

No contract, validator, prompt, data, CLAUDE.md or AGENTS.md change is made by this note.
