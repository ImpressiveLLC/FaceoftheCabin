# E3 contrast fixtures (candidate rows, not gold)

Work order P-3 of [the prompt prior-art plan](../../../proposals/ask-intent-prompt-prior-art-and-improvement-plan.md), ordered on #244 (Cowork, 2026-11-15 15:27 CST). Authoring only: no model was run on these rows.

`e3-contrast.jsonl` holds 16 rows in the same shape as `gold.jsonl`, plus `pair`, `role` (`control`, `contrast`, `near-miss`), `kind` and a one-line `reason` for every row. Every text is invented for this file; none equals a gold text (checked by normalised match) and the sealed sets were not read. Provenance: project-authored, no outside text, `review_status: pending`.

These rows are **not gold and are kept apart from the 49**. Nothing here joins `gold.jsonl` until Nate approves, row by row. The candidate is reported on the 49 and on this set separately; the evaluator already takes the file with `--gold contrast/e3-contrast.jsonl`.

## What is in it

| Pair or group | Rows | What it tests (lesson row in the plan) |
|---|---|---|
| A, B (nonexistent room) | e3-01..04 | A control and a contrast that differ only in a room that is not in `areas.json`. Expected on the contrast row: clarify, never act. Critical. (DS-IA, nonexistent entity forced onto a similar real one) |
| C (card conflict) | e3-05, 06 | Text that conflicts with the selected card must clarify with empty slots. Critical on the contrast row. |
| D (quantity with no source) | e3-07..09 | A readable device value (control) against an outside forecast and an electricity price the system has no source for: `answer_question`, never a number. Critical. (hallucinated weather) |
| E (no claim of success) | e3-10, 11 | "Did that work?" and "Is it done yet?" have no earlier action: clarify. Critical. (claimed success) |
| N (near-miss) | e3-12..16 | Surface of one intent, meaning of another: a command that is really a need, a question that is really a command, "it is late" that is not a schedule, a command that is really a state question, a negation about asking not acting. |

## What the evaluator does not check

The evaluator scores intent, slots and the schema, not wording. Rows with `no_claim_check: true` (e3-10, e3-11) also require that the clarification text does not state or imply that anything was done or succeeded; that is read by hand from the report today and is not scored. An automatic phrase check would be a change to `evaluate.py` and is not part of this PR.

Expected-on-the-current-prompt: the prompt has no room list and no device registry, so the nonexistent-room rows may fail on `system.txt`. That is a measurement, not a defect of the rows. The rooms and the controls were not tuned to any model output.

## Self-check

The 16 targets pass `contract.validate` (with the card where present). Scoring the targets as predictions with `evaluate.py --gold contrast/e3-contrast.jsonl` gives intent accuracy 1.0, slot F1 1.0, critical failures 0, as it must.
