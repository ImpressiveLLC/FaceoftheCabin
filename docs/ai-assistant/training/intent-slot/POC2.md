# POC2: offline language contract extension

This extends the r1 starter under D27 and the GitHub handover
`docs/governance/handovers/HO-2026-10-14-cowork-to-codex-and-code.md`.
It is tooling and provisional fixtures, not a trained or validated model.

## Needs and targets

`express_need` requires a `concept` slot and confirmation. The vocabulary lives in
[`concepts.json`](concepts.json): `cold`, `hot`, `humid`, `dark`, `light`, `arriving`, `leaving`,
`bedtime`, `nap`, `wake_up` and `tv_time`. The first eight are the D27 seed set awaiting Nate's
confirmation; `light`, `nap` and `wake_up` were added by Nate on 2026-10-16 while grading, and `bright`
and every derivation of light or bright (brighter, brighten, brightness, lighting...) are **aliases** of
`light`, not separate concepts: one observational concept, different aliases (Nate, 2026-10-16). The aliases
are shown to the reviewer and used in the corpus. Wording decides between neighbours: `dark` states a lack of light,
`light` states a want for it; `bedtime` is going to bed for the night, `nap` a short daytime rest, and
`wake_up` waking or getting up now (“wake me at 7” is a schedule, not a need). “I'm cold” classifies as
`express_need/cold`. A concept may appear only on `express_need`; a direct command such as “turn on the
hall lights” stays `set_device_state`, and the grading screen shows which needs such a command would
satisfy as read-only context.

Rooms and sites are a closed vocabulary too ([`areas.json`](areas.json)): the grading screen normalizes
`Mechanical Room`, `mech-room` and `the mech room` to `mech room`, refuses a room it does not know, and
`prepare_sft.py` refuses training rows that are not canonical. This is training-data hygiene, not the live
inventory: the resolver still matches against the real device list.
The model must not choose an actuator from a need. The ontology walk and policy
layer supply options; W-75 separately implements the limited cold proof of concept.

`request.schema.json` defines JSON input with `text` and optional `card_target`.
The trusted caller supplies the selected semantic device name, location and site;
the model cannot change them. Validation rejects conflicting output target slots
and automation action targets. Omitting output target slots is allowed: a future
resolver must retain the original trusted card selection. This package does not
implement that resolver or authorize a target. Prompt text cannot provide trust.
Generation, SFT export and local evaluation all retain this same request envelope.

## Schedules and triggers

The existing r1 predicate trigger remains supported. New alternatives are:

- `time_once`: `at`, an ISO datetime with an explicit UTC offset.
- `time_recurring`: `start_time` with offset and an RFC 5545 RRULE value beginning
  `FREQ=`. Validation parses without expanding the recurrence and rejects duplicate
  rule parts, nonpositive count/interval and combined COUNT/UNTIL.
- `state_changes_to`: semantic `target`, `attribute`, `value` and optional
  nonnegative integer `for_seconds`. Presence can be a standalone state target.

Offset datetimes specify fixed offsets; named-zone/DST interpretation is not part
of this offline contract. Missing time or zone requires clarification. Runtime
translation and recurrence execution belong to WorkflowRuleService and require
separate validation; nothing here schedules jobs.

## Fixtures and limits

There are 47 gold fixtures: 41 reviewed by Nate (gold v2, 2026-10-17) and gold-43..48 added 2026-10-16 for `light`, `wake_up`, `nap` and their boundaries, still pending review. They include each concept, all new trigger
forms, immediate/delayed presence, selected cards, guarded valve opening,
unsupported unlocking, wrong site, negation, injection and unitless temperature.
Synthetic templates also generate each new trigger. No live entity IDs are used.
All candidates remain unreviewed and gold remains pending human review. The tiny
seed set is not an independently reviewed quality benchmark.

The existing evaluation thresholds and `release_eligible: false` remain unchanged.
Tests with oracle predictions check the evaluator, not model accuracy. No training,
Ollama/hardware benchmark, live integration, ratification or deployment is performed.
W-75 must supply model accuracy, M920q p50/p95 latency and zero wrong-target evidence
before a live rollout is considered.
