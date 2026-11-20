# express_need `requires_confirmation`: which side changes (proposal, 2026-11-20)

Status: **proposal only; nothing changed.** This note was requested by Cowork on #244 (7030937825). It records a mismatch already on main at `3e441f5b`. It does not edit `system.txt`, `CLAUDE.md`, any contract, schema or training row. Training NOT_RUN.

## The mismatch

The **executable contracts** require `requires_confirmation: true` for `express_need`. Four places agree:

- **Java:** [IntentSlotContract](../../../cabin-orchestration-platform/backend/src/main/java/com/cabin/orchestrator/assistant/IntentSlotContract.java) lists `express_need` in `MUTATIONS` (line 37) and rejects a mutation without confirmation (line 89, "mutations require confirmation").
- **Python:** [`contract.py`](../training/intent-slot/contract.py) `validate` raises "Mutations require confirmation" for the same four intents (lines 18-22). Its builder sets `requires_confirmation` true for them (lines 79-81).
- **Prompt:** both `system.txt` copies, the backend resource and the training copy, say "Needs require confirmation; never choose a device or action from a need alone" (line 26).
- **Data:** every authored `express_need` candidate (S2 batch 4, #531) and the r10/r11 prompt examples use `true`. The batch-4 note says outright that it does not adopt CLAUDE.md's older example.

The **specification** in [`CLAUDE.md`](../../../CLAUDE.md), "POC2 handoff: `express_need`", shows `"requires_confirmation": false` in its example envelope. It labels that envelope "Proposed extension ... (not valid under r1 yet)". The section is marked "Specification only; implementation pending" and was last edited 2026-11-15.

## Proposal

**Change `CLAUDE.md`, not the contracts or the data.** In the POC2 example, replace `false` with `true`, and add one sentence: an expressed need is treated as a mutation request for confirmation purposes, because any response to it, such as offering a heater, is an action the person must confirm. That matches the spec's own rule "do not invent an action from a feeling", the prompt's rule, and both validators.

**Why this side changes:**

- The `false` example would fail both validators today. Any row written to it is invalid, so the contracts can't be the side that's wrong without a code change in two languages.
- Confirmation is the safer default for an intent whose only possible follow-up is an action. Changing to `false` would let a "proposal" path skip a human yes. That is a safety-relevant behavior change, and this note does not propose it.
- Every `express_need` row in the S2 candidate pool already uses `true`. Changing the contract instead would invalidate them all.

**If Nate wants `false` instead,** for example because the route only ever answers with a need name and offers no action, that is a contract change. It needs its own reviewed PR touching `IntentSlotContract.java`, `contract.py`, both `system.txt` copies, the schema and every affected row, plus a decision on how confirmation then applies when an action is offered. It should not be done by editing CLAUDE.md alone.

## Requested ruling

Cowork, or Nate as owner of CLAUDE.md: accept the one-value CLAUDE.md edit (proposed above), or choose the contract change. The CLAUDE.md edit itself is a separate small PR after the ruling.
