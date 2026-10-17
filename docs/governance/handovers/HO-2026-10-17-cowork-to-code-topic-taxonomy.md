# HO-2026-10-17: Cowork to Claude Code, topic taxonomy evaluation harness (execute now)

Baseline: `origin/main` 9a823f7 (2026-10-17). Nate, 2026-10-17: concurred with the plan and asked for the handover to be ready in git so Code can run it as soon as possible.

**Read first:**
- `.claude/skills/cabin-peer-agent-collaboration/SKILL.md` (freshness, id checks, merge rules)
- [`proposals/2026-10-17-topic-taxonomy-evaluation.md`](../proposals/2026-10-17-topic-taxonomy-evaluation.md) (the plan; it owns every threshold, schema and gate, and this file does not repeat them)
- D16 in `docs/governance/decisions/ontology-decisions.md`
- `docs/governance/definition-of-done.md` (the PR governance record)

## Evidence at baseline (verified 2026-10-17, do not assume more)

- `docs/ontology.yaml` carries 73 raw, 61 derived, 20 composite and 73 conceptual elements (counted by Cowork).
- No file in the repo contains the term "question modifier". Ask's closest object is the 18 context categories in `context-fixtures-r2.json`.
- **Not verified here, check at G0:** the five D16 topics as currently implemented, the Ask category count, and that the UI does not surface the raw/derived/composite distinction.
- Connectivity has no captured measurements, so its questions use a `no data yet` expected state. Light waits on #174.

## Step 0: land this PR (docs only, merge first)

This PR allocates W-89 to W-92 and adds the plan under `proposals/`. Nate's rule: ids are allocated in git before work starts.

1. Before pushing, re-check W-89 to W-92 against `main` and every open branch (README, ID allocation). At baseline, open PRs held W-66, W-68, W-69, W-75, W-77 to W-81, W-84 to W-88; renumber here if any id is now taken.
2. Merge once the PR is clean and Nate approves.

## Work, in order

Each step is its own PR from fresh main and carries the DoD governance record. The first wave is W-89 to W-92; plan items E to L get ids from the PR that introduces them.

| # | Item | Id | Gate | Machine | Output |
|---|---|---|---|---|---|
| 0 | Verify the baseline | none | G0 | ilikethelights | A short report on the PR: differences from the evidence list above |
| 1 | Evaluation harness | W-89 | G1 | ilikethelights | `eval/taxonomy/` question set, verdict schema, labeling protocol; edge-test drafts for the D16 amendment |
| 2a | Blind labeling | W-90 | G2 | ilikethelights | Two label files, agreement report, ambiguity list, audit sample |
| 2b | Blind probe | W-91 | G3, G4 | M920q for model runs | Runner, run logs, per-question four-cell report, control results |
| 3 | D16 amendment | W-92 | G5 | ilikethelights | Held until G4 passes |

Steps 2a and 2b run in parallel after G1. Machine rules follow `fotc-git-and-host-routing`: git, docs and test work on ilikethelights; model runs on the M920q, not while training or an eval run is active.

## Rules that are easy to get wrong

- The probe sees topic names and one-line descriptions only. No edge rules, no rulings, no rationale.
- The harness computes `found` from `expected_topic`; the probe never reports it.
- The verdict schema accepts every reason value in the plan, and every cross-field rule is enforced. A record that fails is discarded and counted as an instrument defect.
- Rulings (R3, R4) apply at analysis time and never edit a run log.
- Labeling is blind: do not read Cowork's labels or the proposed mapping before submitting yours.
- Local open models may serve as a third labeler or probe worker. They never rule; rulings are Nate's.
- Edge tests live in the D16 amendment, not in a separate document. Run logs are generated, never hand-edited.
- Thresholds in the plan are proposals until Nate ratifies them at G1.

## Report back with

Baseline differences, the question set, schema validation results, and any of R1 to R11 in the plan that you can answer from the repo (R1, R8 and R9 first).

## Closing

The PR that closes the last task deletes this file, and its commit message lists the outcome PRs. Git history keeps the file.
