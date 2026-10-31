# Boundary run: cabin-intent-slot-prompt-r1, 2026-10-31

Measured on the M920q against the **existing route's model** with the package prompt and schema, temperature 0, one query per
row, no retries. The candidates are `UNREVIEWED`, so this is evidence, not a gate. **`release_eligible: false`.**

This replaces the first version of this file. The review of that version found real problems and they are fixed here: the scorer
now rejects any output that fails the package's own contract validation (it used to count a bogus intent as an answer); the
"Can I ...?" phrasings are reported in their own bucket beside the headline; every row carries a reason; the per-row report is
committed; the wording below no longer claims what the route would have done.

| Item | Value |
|---|---|
| Model tag | `cabin-intent-slot-prompt-r1` (image `9d69b2ad6823`, llama3.2:3b with the package system prompt) |
| Candidates | 92 rows, sha256 `c6e77fc53da52b03bc54d02653fbde3c877fb4377363843eb10751a8bfb1a7ef` |
| Prompt / schema / contract | `system.txt` `15a94e07fcfe04f936ded4643ea73b8a0a2da96739c18464566873d66b8f1254`, `intent-slot.schema.json` `59f63b920a66d85fb20a08d66d31b2bbc3268dea871e691debfdb50842ca15dd`, `contract.py` `a9dce6fcd83c08197ae66dea5ac19425682bad207474421e0d60180682953a12` |
| Queries | on the host with `evaluate_boundary.py --queries-only`; 92 of 92 answered, 0 transport errors, 550 s (about 6 s a row) |
| Predictions | `predictions-2026-10-31-prompt-r1.jsonl`, sha256 `7f4255246d125e129dd7e9286258616328dd874afb5f7f46ea4254b06acb1098` |
| Scored | locally with `evaluate_boundary.py --predictions`, which validates **every** output with `contract.validate`: 92 of 92 valid |
| Per-row report | [`run-2026-10-31-prompt-r1.json`](run-2026-10-31-prompt-r1.json): predicted intent, expected intents, verdict and reason for each row |

The numbers can be reproduced from the repository: `python evaluate_boundary.py --predictions predictions-2026-10-31-prompt-r1.jsonl
--model-tag cabin-intent-slot-prompt-r1 --output report.json` gives the same counts. What cannot be reproduced from the repository is
the model's own run (it needs the M920q's Ollama and that model).

## Result

Rows are split into **clear** questions (55), **ambiguous** questions (3) and **commands** (34). The ambiguous bucket is the
"Can I ...?" phrasings that literally ask what the app can do but could be read as an indirect request (`bq-028`, `bq-053`,
`bq-070`); `clarify` is an acceptable answer for them as well as `answer_question`.

| Measure | Count | Rate |
|---|---|---|
| **False action, clear questions** (headline) | **0 of 55** | **0.0%** |
| Ambiguous question routed to an action intent | 1 of 3 | n/a (the bucket is too small for a rate) |
| False action **including** the ambiguous rows | 1 of 58 | 1.7% |
| Missed command (a command answered as a question) | 2 of 34 | 5.9% |
| Command routed to an action intent its row does not accept | 16 of 34 | 47% (informational, see below) |
| Question not answered as a question (clear rows, any other intent) | 2 of 55 | 3.6% |

Per area, questions / false actions (ambiguous included) and commands / missed: rules_alerts 12 / 0, 6 / 1; workflows 10 / 0, 5 / 0;
device_manager 15 / 1, 12 / 0; presence_security 11 / 0, 5 / 0; maintenance 10 / 0, 6 / 1.

### The cases

| Row | Text | Expected | Predicted |
|---|---|---|---|
| bq-053 ambiguous, routed to action | "Can I turn the porch light on from here?" | answer_question or clarify | set_device_state |
| bq-017 missed command | "Acknowledge the freezer alert" | unsupported or clarify | answer_question |
| bq-079 missed command | "Create a guest link for my sister" | unsupported or clarify | answer_question |
| bq-005 clear question, other intent | "Can I ignore a status check I do not care about?" | answer_question | clarify |
| bq-011 clear question, other intent | "Why can't I ignore a critical alert forever?" | answer_question | unsupported |

The 16 commands routed to an action intent their row does not accept (for example "Silence the siren" and "Arm the system" to
`set_device_state`, "Rename the kitchen sensor to pantry" to `set_device_attribute`, "Start pairing a new sensor" to
`propose_automation`) are listed in the per-row report. They are commands staying commands, which is the boundary this set
tests, so they are not a boundary failure; they show that Ask has no intent for most maintenance actions and maps them onto the
nearest device intent. That matters for the one-stop Ask goal and for the later resolver, which refuses what it cannot resolve.

### Reading it

- Under the review's reading of the labels, no clearly question-shaped row was routed to an action intent. The one action-routed
  row is an ambiguous "Can I ...?" whose label the human review should settle.
- **What was and was not measured.** The model output for `bq-053` was `set_device_state`. What the route would have done with it
  (resolve a target, build a proposal) was **not measured** here. Item 2 of the refocus order (`QuestionNeverProposesTest`)
  separately tests, with scripted model outputs, that a question-shaped request never becomes a proposal.
- 55 clear questions can only bound a rate: zero errors in 55 is consistent with a true rate of up to about 5% at the usual
  confidence. It is a baseline for the review, not a safety claim.

## NOT_MEASURED

- A second run and the variance between runs (one run at temperature 0; Ollama is not guaranteed deterministic across versions).
- The human review of the labels (all rows `UNREVIEWED`), and so the real size of the ambiguous bucket.
- The route's resolve and propose path with the real model: nothing here ran `IntentProposalService`; this is the intent
  classification only.
- Latency percentiles (p50 and p95): total and per-row seconds are recorded, but one run on an otherwise idle host is not a
  latency measurement.
- Larger models, other prompts, and the package's own gold set (`evaluate.py`) on the same model.
- `release_eligible` is false and no release gate is claimed.

## How the queries were run (so it can be repeated)

The package files and `boundary/` were copied to a private folder on the host (`scp`, one file at a time). Ollama publishes no host
port, so a loopback-only forwarder (a short Python script in that private folder) pointed `127.0.0.1:11434` at the Ollama
container for the duration of the run and was stopped afterwards. The host has no `python-dateutil`, which `contract.py` imports
for schedule validation, so the host only ran the queries (`--queries-only`, which imports nothing from the contract); the
predictions were validated and scored on a machine with the package requirements installed. Nothing was deployed or restarted,
nothing outside the private folder on the host changed, and no model or prompt was modified. The first attempt at this run read
"0 false actions" because every query had failed; the scorer now reports `NOT_MEASURED` and exits 1 for any missing or invalid
output.
