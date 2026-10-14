# HO-2026-10-14 — Cowork → Codex and Code: D27 Phase 1 start, PR #159, camera evidence

**Read first:** `.claude/skills/cabin-peer-agent-collaboration/SKILL.md`. It covers freshness, ids, merge rules and conflict repair. Codex and Code work this list in parallel through git: claim an item by opening its branch or PR, then re-fetch before you touch anyone else's branch.

**Source of truth:**
- [D27](../decisions/ontology-decisions.md), ratified 2026-10-14 for the Phase 1 POC start, with Nate's answers recorded
- [UC-17](../use-cases.md)
- backlog W-66 to W-75
- [Ask backlog](../../ai-assistant/wsjf-backlog.md) POC2 and C5

Remaining questions Q-OC-6 to Q-OC-11 are in D27. Cowork answers those, except Q-OC-11, which is Nate's.

**Gates Cowork holds:**
- Nothing executes from Ask except the W-75 heater scope.
- Every Ask or card action is confirmed (R-OC-7, R-OC-11).
- Guards are enforced server-side (R-OC-4).
- No entity ids come from the model (R-OC-5).
- `CLAUDE.md`'s automation rule changes only in the W-71 PR, with the Q-OC-5 carve-out: cabin leak, freeze and siren automations stay in Home Assistant.

## Order (WSJF, then dependencies)

| # | Item | Owner | WSJF | Starts when |
|---|---|---|---|---|
| 1 | **W-74** PR #159 fix | Codex | 21.0 | now |
| 2 | **POC2** intent/slot extensions | Codex | 10.0 | #159 merged |
| 3 | **W-75** vertical POC: "I'm cold" → heater option → one-tap confirm → run | Codex builds; Code reviews | 11.3 | POC2 gold exists (shadow first) |
| 4 | **W-66** concept vocabulary (POC needs `cold` only) | Cowork drafts in a PR; Code stores with W-68 | 9.7 | now |
| 5 | **W-67** inventory API design note, including the Q-OC-9 Home actuation check | Code | 8.7 | now |
| 6 | **W-69** controls and guards design note (direction-aware, Q-OC-2) | Code | 7.6 | now |
| 7 | **W-73** presence as an atomic State subject, design note (Q-OC-7) | Code | 6.6 | now |
| 8 | **W-68** metadata store design note | Code | 5.2 | now |
| 9 | W-70 views, W-72 card composer, W-71 engine (schedules/triggers) | Code | 4.8 / 4.6 / 3.9 | after the design notes above are ratified |

Design notes are docs PRs. Cowork ratifies them before any build, and W-75 is the one exception. Merge per the skill: an agent merges its own docs or POC PR when it is clean and carries no named Nate condition.

## Codex

1. **W-74 (on #159's branch).** Re-read the remote head first.
   - In `docs/ai-assistant/training/intent-slot/README.md`, replace "HA remains the automation engine; coordinate automation persistence with CabinAutomations, not a parallel engine" with: "Where saved automations run is decided in D27 (Q-OC-5): user-authored schedules and triggers run in the platform workflow engine at both locations; cabin leak, freeze and siren automations stay in Home Assistant."
   - Make the matching change to "Reuse existing executor and HA automation ownership" in `IMPLEMENTATION-ISSUE.md`.
   - Run the package tests (12 passed at 37d9b86) and push.
   - Merge when the PR is clean. Nate asked for this to move fast, and #159 is offline tooling.
2. **POC2.** Branch `codex/intent-slot-poc2` from fresh main after #159 merges. Scope is the POC2 section of the Ask backlog. Tests must pass offline. Commit: `feat(ai-assistant): intent/slot r1 POC2 -- needs, schedules and event triggers (D27, POC2)`.
3. **W-75.** Branch `codex/w75-im-cold-poc`. The backlog row has the full scope. In short:
   - **Gating:** a feature flag, off by default. Admin session only. Cabin only.
   - **Model:** the POC2 prompt-only model (`Modelfile.intent-r1` plus the POC2 system prompt) via local Ollama.
   - **Concept map:** static, checked-in data (`cold` → raise heat → capability `turn_on` on the mech room heater).
   - **Resolution:** against the live device list. It returns exactly one target or refuses.
   - **UI:** a single option card in Ask (R-OC-11). One tap confirms. The confirmation is bound to principal, plan hash and a 5-minute expiry (Q-OC-10).
   - **Execution:** only through the existing `/api/ha/services` path, for the heater switch only.
   - **Rollout:** shadow mode first, logging model output, resolution and outcome. Then live.
   - **Backend checks:** run the whole suite. A new route needs its `DemoAccessPolicy` and `WriteGateAuditTest` entries.
   - **Evidence on the PR:** POC2 gold accuracy for `express_need`/`cold`, p50/p95 latency on the M920q, and a wrong-target count of 0.

## Code

1. **Open the D27 proposal PR** if it isn't open yet: `cowork/d27-ontology-context-actions` carries this file. Merge it when clean. D27 is ratified with Nate's answers; the open questions are recorded, not blocking.
2. **Post the #159 review** below as a comment, unless Codex has already fixed item 1.
   - *The safety design is right:* semantic slots only, the resolver fails closed, confirmation is bound to plan hash, principal and expiry, `evaluate.py` never claims release eligibility, and training uses an isolated tag.
   - *Item 1:* the "HA remains the automation engine" line conflicts with D27 Q-OC-5 (W-74).
   - *Follow-on, POC2:* only device-value triggers exist today. Missing are schedules (once, RRULE), `changes_to` / `for` triggers, `express_need` + `concept`, and `card_target`.
   - *Note:* `intent-slot-tooling.yml` is the first `pull_request` workflow. It is GitHub-hosted, read-only and offline, which is fine.
   - *Gold:* gold labels need human review before any holdout is frozen.
3. **Design notes** W-67, W-69, W-73 and W-68, in that order:
   - DDL in the existing `CREATE TABLE IF NOT EXISTS` style
   - routes with their demo-policy and write-gate entries
   - reconciling the `docs/ontology.yaml` relation enum
   - Q-OC-9 (what can actuate at Home, with no HA there)
   - Q-OC-7 (state identity)
   - the W-45 open questions folded into W-69
4. **Camera evidence (M920q, read-only).**
   - **W-5:** detection has run on `cabin_outside_reolink` since 2026-10-10 06:33 UTC, but no baseline is posted. Post per-day counts for 2026-10-10 to 2026-10-14: Frigate events by label from `/api/events`, and any misses found by scrubbing the recordings. Nate supplies the Reolink app motion counts.
   - **Scenario review** on or after 2026-10-24: a large object falling onto a car in view, and a person at the road for a minute or more, day and night. For each: an event, recording only, or missed.

_Cowork, 2026-10-14._
