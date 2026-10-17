# POC2: offline language contract extension

This extends the r1 starter under D27 and the GitHub handover
`docs/governance/handovers/HO-2026-10-14-cowork-to-codex-and-code.md`.
It is tooling and provisional fixtures, not a trained or validated model.

## Needs and targets

`express_need` requires a `concept` slot and confirmation. The seed concepts
`cold`, `hot`, `stuffy`, `dark`, `arriving`, `leaving`, `bedtime` and `tv_time`
await Nate's confirmation. “I'm cold” classifies as `express_need/cold`.
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

There are 42 provisional gold fixtures, including each concept, all new trigger
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
