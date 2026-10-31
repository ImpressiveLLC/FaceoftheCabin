# Question-versus-command boundary: candidate set and false-action rate (W-117, boundary item)

**Status: every row here is `UNREVIEWED`.** These are project-authored candidates for human review (Nate or Codex). A reviewer
promotes a row to gold by reviewing it and moving it into `../gold.jsonl` with the usual `provenance` block; nothing here is gold,
nothing is trained on, and no schema or model changed.

## Why

`../gold.jsonl` has 49 cases and only 4 are `answer_question`. There was no gold for question-shaped text about rules, workflows,
alerts, silencing, the Device Manager states, presence or storage, so nobody could say whether a household member asking *how*
or *why* about the app reaches `answer_question`, or measure how often a question is mistaken for a command. This set covers
that gap in plain words.

## What is in `boundary-candidates.jsonl`

92 rows: **58 questions** (expected intent `answer_question`) and **34 commands** that must stay commands. Most commands are the
near-miss twin of a question (`contrast_of` points at it, and the pair shares a `group` so a split never separates them), for
example "How do I turn off the heater from the app?" against "Turn off the heater", or "Can I silence the siren?" against
"Silence the siren". Five more are polite or indirect commands ("Can you turn off the heater?").

Areas, as named in the refocus order: `rules_alerts` (Active Conditions, Status Checks, Ignore / Remind me in / Restore,
silenced items), `workflows` (create, activate, fire, undo, triggers, actions), `device_manager` (add, remove, pair, the
lifecycle states, Parent devices only, Imported from), `presence_security` (presence badge, armed away, primary and maintenance
people) and `maintenance` (storage, backups, trim, guest links, restart, tokens, versions).

A command row lists the intents that are acceptable (`expected_intents`): `set_device_state` for a device, and for an action Ask has
no intent for (silencing an alert, restarting the backend) `unsupported` or `clarify`. The one hard rule is that a command is
**never** `answer_question`.

## The number

`evaluate_boundary.py` scores a run. **False action** = a question-shaped row whose predicted intent is `set_device_state`,
`set_device_attribute`, `propose_automation` or `express_need`. **Missed command** = a command row predicted as `answer_question`.
It queries only local Ollama through the package's own `evaluate.query` (the same strict-schema call), or scores a predictions
file. A run with any row missing an output reports the rates as `NOT_MEASURED` and exits non-zero: a rate over failed queries
would otherwise read a reassuring zero (the first attempt at this run did exactly that, every query failed, and is why the check
exists). See [RESULTS.md](RESULTS.md) for the measured result.

```sh
python -m unittest test_boundary -v                      # fixtures and scoring, no model
python evaluate_boundary.py --model cabin-intent-slot-prompt-r1 --output report.json   # needs local Ollama
```

## Limits

- Unreviewed candidates: the rates are evidence, not a release gate. Labels such as "Can I turn the porch light on from here?"
  (a how-to, or an indirect request?) are exactly what the review should settle.
- Wording is mine; a household member's wording will differ. The vocabulary crosswalk (refocus item 3) is the place to widen it.
- One model tag, one run, temperature 0. Not a latency or quality claim.
