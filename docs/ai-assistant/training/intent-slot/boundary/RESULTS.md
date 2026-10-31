# Boundary run: cabin-intent-slot-prompt-r1, 2026-10-31

Measured on the M920q against the **existing route's model** with the package prompt and schema, temperature 0, one query per
row, no retries. The candidates are `UNREVIEWED`, so this is evidence, not a gate.

| Item | Value |
|---|---|
| Model tag | `cabin-intent-slot-prompt-r1` (image `9d69b2ad6823`, llama3.2:3b with the package system prompt) |
| Candidates | 92 rows, sha256 `23371d07b8316381...` (full hash in the run folder's report) |
| Prompt / schema | `system.txt` `15a94e07fcfe04f9...`, `intent-slot.schema.json` `59f63b920a66d85f...` (the files in this folder's parent) |
| Completed | all 92 queries answered, 0 transport errors, 529 s in total (about 5.8 s a row) |
| Report | private run folder on the host (`boundary-run-20261031-private`), report sha256 `d271f475dfe4ed6c...`; raw outputs are not committed |

## Result

| Measure | Count | Rate |
|---|---|---|
| **False action** (question routed to a command or proposal intent) | **1 of 58** | **1.7%** |
| Missed command (command answered as a question) | 2 of 34 | 5.9% |
| Question not answered as a question (any other intent) | 3 of 58 | 5.2% |

By area (questions / false actions, commands / missed commands): rules_alerts 12 / 0, 6 / 1; workflows 10 / 0, 5 / 0;
device_manager 15 / 1, 12 / 0; presence_security 11 / 0, 5 / 0; maintenance 10 / 0, 6 / 1.

### The cases

| Row | Text | Expected | Predicted |
|---|---|---|---|
| bq-053 **false action** | "Can I turn the porch light on from here?" | answer_question | set_device_state |
| bq-017 missed command | "Acknowledge the freezer alert" | unsupported or clarify | answer_question |
| bq-079 missed command | "Create a guest link for my sister" | unsupported or clarify | answer_question |
| bq-005 not a question answer | "Can I ignore a status check I do not care about?" | answer_question | clarify |
| bq-011 not a question answer | "Why can't I ignore a critical alert forever?" | answer_question | unsupported |

### Reading it

- The one false action is the kind of phrase the review should rule on: "Can I ... from here?" can be a how-to or an indirect
  request. On this wording the model treated it as a request and would have proposed `set_device_state` (with confirmation, since
  the route only proposes). If the reviewer agrees it is a question, that is a real false action; if not, the rate is 0 of 57.
- Every other question-shaped row, including all the "How do I turn off ... / Why is ... closed" twins of a real command, stayed
  off the command and proposal intents. Questions that were not answered as questions went to `clarify` or `unsupported`, not to
  an action.
- The two missed commands are actions Ask has no intent for; answering them as a question is wrong but harmless (nothing is
  proposed). They matter for the one-stop Ask goal, not for safety.
- 58 questions can only bound a rate: one error in 58 is 1.7%, and the true rate could be several times higher. It is a baseline
  for the review and for item 2 (the end-to-end "a question never yields a proposal" test), not a safety claim.

## How the run was done (so it can be repeated)

The package files and `boundary/` were copied to a private folder on the host (`scp`, one file at a time). The host has no
`python-dateutil`, which `contract.py` imports for schedule validation; this script never validates schedules, so an import-only
stub was placed in that private folder (not in the repository). Ollama publishes no host port, so a loopback-only forwarder
(a short Python script in the same private folder) pointed `127.0.0.1:11434` at the Ollama container for the duration of the run and
was stopped afterwards. Nothing was deployed, restarted or changed on the host outside that private folder, and no model or
prompt was modified.
