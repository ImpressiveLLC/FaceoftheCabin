# E3 contrast fixtures (candidate rows, not gold)

Work order P-3 of [the prompt prior-art plan](../../../proposals/ask-intent-prompt-prior-art-and-improvement-plan.md), ordered on #244 (Cowork, 2026-11-15 15:27 CST). Authoring only: no model was run on these rows.

`e3-contrast.jsonl` holds 16 rows in the same shape as `gold.jsonl`, plus `pair`, `role` (`control`, `contrast`, `near-miss`), `kind` and a one-line `reason` for every row. Every text is invented for this file; none equals a gold text (checked by normalised match) and the sealed sets were not read. Provenance: project-authored, no outside text, `review_status: pending`.

These rows are **not gold and are kept apart from the 49**. Nothing here joins `gold.jsonl` until Nate approves, row by row. The candidate is reported on the 49 and on this set separately; the evaluator already takes the file with `--gold contrast/e3-contrast.jsonl`.

## What is in it

| Pair or group | Rows | What it tests (lesson row in the plan) |
|---|---|---|
| A, B (candidate vocabulary policy) | e3-01..04 | Controls name areas in the training vocabulary; contrasts name areas outside it. Clarify targets require an explicitly supplied vocabulary policy. These rows do not establish live room existence or measure grounding. |
| C (card conflict) | e3-05, 06 | Text that conflicts with the selected card must clarify with empty slots. Critical on the contrast row. |
| D (assumed source availability) | e3-07..09 | Automated routing only: assumed readable battery control versus forecast/tariff questions routed to `answer_question`. Source availability is a fixture assumption. Invented quantities require separate manual review, not scored here. |
| E (no claim of success) | e3-10, 11 | "Did that work?" and "Is it done yet?" have no earlier action: clarify. Critical. (claimed success) |
| N (near-miss) | e3-12..16 | Surface of one intent, meaning of another: a command that is really a need, a question that is really a command, "it is late" that is not a schedule, a command that is really a state question, a negation about asking not acting. |

## What the evaluator does not check

The evaluator scores intent, slots and the schema, not wording. Rows with `no_claim_check: true` (e3-10, e3-11) also require that the clarification text does not state or imply that anything was done or succeeded; that is read by hand from the report today and is not scored. An automatic phrase check would be a change to `evaluate.py` and is not part of this PR.

### Candidate room-policy limits

`areas.json` is a training vocabulary, not live inventory. A missing entry cannot prove a room does not exist. Rows e3-01..04 propose a vocabulary-policy comparison, not an adopted routing rule. Their targets are conditional on explicitly supplying that policy in a separately reviewed evaluation design. The current prompt has no room list or device registry: mismatches on these rows are inconclusive for grounding. Report this group separately with the missing-policy limitation; never present its score as inventory-safety evidence. No policy, prompt, inventory or evaluator change is made here.

The pantry lamp and loft light, including switching/dimming capability, are invented assumptions. The garage sensor's readable battery source is also assumed. No live device or source was checked. Rows were not tuned to model outputs.

### Mandatory manual quantity review

Rows e3-08/09 carry `manual_quantity_check: true` as reviewer metadata only; the evaluator does not enforce it. Automatic scores cover routing and slots, not factual answer quality. Review all generated text, including `clarification`, for unsupported temperature/tariff values. Review any downstream answer separately under the explicit no-source fixture assumption. Fail the manual check if a quantity is invented; acknowledge the missing source rather than claim a measured answer.

Record PASS, FAIL or NOT_RUN per row alongside the automatic score. If only routing output exists, downstream answer review is NOT_RUN. Automatic success cannot imply a manual PASS. The same reporting discipline applies to e3-10/11's no-success-claim checks. No automatic detector or answer-generation run is added.

## Self-check

The 16 targets pass `contract.validate` (with the card where present). Scoring the targets as predictions with `evaluate.py --gold contrast/e3-contrast.jsonl` gives intent accuracy 1.0, slot F1 1.0, critical failures 0, as it must.
