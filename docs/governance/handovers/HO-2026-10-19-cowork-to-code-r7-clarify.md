# HO-2026-10-19 — Cowork → Code: round r7 (prompt-only `clarify` rule)

Status: proposal for Code to run; Nate said "next step" on 2026-10-19 after merging the graded gold (#209: 49 rows, all human-approved). Nothing here is trained, deployed or enabled.

## Why now
Gold changed (49 approved rows incl. `humid`), so r6 is no longer comparable. r7 is the fresh baseline **and** the first test of a `clarify` rule. r6 reference (47 fixtures, old gold): clarify 3/9, intent accuracy 83.0%, slot micro F1 57.1%, 15 critical failures; confusions `clarify->set_device_state` 4, `clarify->set_device_attribute` 1, `clarify->propose_automation` 1.

## Step 0 — baseline, no prompt change
Run the **current** `system.txt` on the new gold first and record it as `r7-baseline`. Without it a gain from the rule cannot be told from the gold change.

## Step 1 — the rule (prompt only, `system.txt` + its Java copy + mirrors; contract hash changes, so expect a new journal)
Add one short decision order to `system.txt` before the examples, then 3 to 4 examples. Proposed text, for Code to tighten:

> Before choosing a device command, check four things in order: (1) Is a device named, or a card selected? A pronoun ("it", "that"), a generic noun ("the light") with no room, or nothing at all means `clarify`. (2) Does the request carry both an action and, for a number, a unit? Missing unit is `set_device_attribute` with a unit question. (3) Is it negated ("do not", "never")? `clarify`; never turn a negation into a command. (4) Does it conflict with the selected card or site (for example "at home" on a cabin card)? `clarify` with empty slots. Only when all four pass is `set_device_state` or `set_device_attribute` correct. A schedule ("wake me at 7") is `propose_automation` only when the action is stated; otherwise `clarify` asks what should happen.

Failing rows it targets (r6, text → want):
- gold-13 "Turn it down" → clarify; gold-15 "Activate the light" → clarify (checks 1)
- gold-37 "Turn on the heater at home" → clarify (check 4); gold-38 "Do not turn on the heater" → clarify (check 3)
- gold-40 "Set the selected heater to 72" → clarify (unit and target to confirm); gold-47 "Wake me at 7 tomorrow" → clarify
- **Not fixed by this rule, review separately:** gold-14 "Set the thermostat to 19" (gold says `set_device_attribute` while the clarification text mentions a default room: decide whether the label or the prompt is right), gold-48 "Turn on the hall lights, it is dark in here" (command plus need in one sentence: gold says `set_device_state`; model said `express_need`).

## Do not
- Do not tune examples to the exact gold sentences. Write new phrasings; gold is dev, and the sealed test set (S3) is what counts later.
- Do not add vocabulary, slots or intents. Prompt text only.
- No fine-tuning (proposal #199 section 9: after r7 and r8 plateau).

## Report back (ledger row via `round_ledger.py`)
`r7-baseline` and `r7` each: intent accuracy, slot micro F1, critical failures, per-intent (clarify first), confusion pairs, M920q p50/p95 latency, wrong-target count (must be 0), prompt and gold hashes. Runtime gates not run stay `NOT_MEASURED`, `release_eligible: false`. Run on the M920q only when nothing else is using it.

## Decision for Nate
gold-14 and gold-48: are the labels right? If a label changes, grade it again and press Done; the baseline then needs a rerun.
