# Guest-link context tidy and C3 prerequisites (proposal, 2026-11-18)

Status: **proposal only; nothing implemented.** Requested by Cowork on #244 ([6990654148](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6990654148)). Pinned to `main` 159daa8bae33d907d6c150c375872d732eddfa04. No code, routing map, guide, matcher, budget or runtime change. A1 NOT_MEASURED, `release_eligible` false, training NOT_RUN.

The document has two parts. Part A proposes how to handle the guest-link context loss that the tail fixes created; it is meant to support one ruling that also answers Codex's open question on #515's candidate ([6990710391](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6990710391)). Part B lists what has to be true before C3 (one-stop Ask) starts.

## Part A: guest-link context tidy (P3)

### What happens today

The tail fixes use zero-boost, three-word installation phrases that outscore `access_sharing`'s two-word "guest link". When one question contains both, the installation category wins outright and the two sharing sections (administrator-only) are no longer delivered.

| Question | Before the phrase | After | Source |
|---|---|---|---|
| "Did the installation acceptance pass for the guest link?" | `access_sharing` + `recreation_acceptance`: both sharing sections plus the acceptance record | `recreation_acceptance_record`: the acceptance record only | merged #512; Code's review [6989827054](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6989827054) |
| "What is the installation plan for a guest link?" | `access_sharing` + `recreation_plan`: both sharing sections plus the plan | `installation plan for` candidate: the plan only | #515 candidate, not yet implemented; reproduced in [6990566088](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6990566088) |

Both wordings are contrived. Neither is in the 150 coverage-map phrasings or the 103 development rows, and both affect administrators only, because every section involved is administrator-only. Still, the outcome is wrong: someone asking about a guest link loses the guest-link guidance.

### Options

1. **Accept and pin (Codex's option A).**
   - The behavior is recorded as a known limitation.
   - A test asserts the *actual* behavior: the installation section arrives whole, and no credential or role regression happens. The test name says sharing context is not delivered.
   - Cost: minutes.
   - Risk: none new. The limitation stays visible and cannot get worse silently.
2. **A tie-preserving guest-link category (a measured design is needed first).**
   - A separate zero-boost category with a three-word guest-link phrase (for example "a guest link" or "the guest link") maps the two existing sharing sections. On a mixed question it would tie the installation phrase, so both are kept.
   - Budget: the plan (2393) plus both sharing sections (2832) is 5225; the acceptance record (1486) plus them is 4318. Both fit within 6000.
   - Cost: a measured probe before any ruling. On plain guest-link questions ("How do I make a guest link?") the new phrase scores 3 against `access_sharing`'s 2. It would deliver the same sections, but it changes the first category. Its interaction with I04's "guest link have" (#505) and with credential wording also needs measuring.
   - Risk: moderate. It touches the most-used sharing questions, so it needs the 253-question six-role comparison and the 120 pins.
3. **Narrow the installation phrases instead.** Rejected. It would move the reported acceptance and plan questions back into their gaps.
4. **A tie or priority rule in the matcher.** Out of scope: the C1 ruling excludes matcher and budget changes.

### Recommendation

- **Now: Option 1 (A)** for both the merged #512 phrase and the #515 candidate, so the plan fix can ship with its limitation pinned honestly rather than masked by a weakened negative.
- **Later: Option 2** as its own small design-then-implementation slice after install_gap_1 lands, measured against the C1 baseline like the other tail fixes.

This answers Codex's A/B question with **A plus a scheduled B-style follow-up**, if Cowork agrees.

## Part B: C3 (one-stop Ask) prerequisites

C3, as recorded in [wsjf-backlog.md](../../ai-assistant/wsjf-backlog.md), augments the verified `/api/helpdesk/ask` controller and the native panel. It adds reviewed document and section citations, explanation, diagnosis, stepwise plans, relevant settings and actions, progress and follow-up context, while keeping existing response fields and `sources` compatibility. Its exit evidence includes real HTTP and browser journeys, task completion, recovery and a clean-room setup walkthrough.

### Where things stand at 159daa8b

| Area | State | Evidence |
|---|---|---|
| Corpus (C2) | Closed: slices 1 to 8 and the tail fixes are merged | #482 to #495; #500 to #507 |
| Retrieval and delivery (C1-S1 and the tail) | Administrator delivered-whole 97 of 98, household 5 of 5, 0 leaks, 0 over budget. One gap (install_gap_1) is being implemented | #499 baseline, #511 checkpoint, #512, #514, #515 |
| Answer quality | **NOT_MEASURED.** The last measured live runs were the C2 runs in October: RUN-1 12 of 72 against 11 of 72; RUN-2 a HOLD with edits reverted; RUN-3 inconclusive | [wsjf-backlog.md, C2 evidence log](../../ai-assistant/wsjf-backlog.md) |
| Sealed acceptance set | Rules settled: Cowork blind author, Codex custody control. The domain verifier, barrier verifier and any real-environment operator are not yet named, and the device is not yet qualified | [custody design](../../ai-assistant/proposals/ask-custody-agent-and-cloud-r3-deferral.md) |
| Response contract | `TinyHelpdeskAnswer(question, answer, sources, answeredByModel)`. Reviewed sections already reach the prompt as `[source: path#section]` and return as `REVIEWED_DOCUMENT` source nodes | `TinyHelpdeskAnswer`, [c1a-iteration-2.md](../../ai-assistant/rag/c1a-iteration-2.md) |

### Prerequisites before C3 starts

1. **C1 closed on delivery.** install_gap_1 is merged, or ruled a permanent limitation, and the C1-S1 baseline gates stay green.
2. **One measured answer-quality result.** This is a gate that has not been met yet. C1's own ratification says it is not complete until a server-side run shows net improvement and Cowork records the result. C3 builds on answers, so starting it on NOT_MEASURED quality would hide whether a C3 change helps or hurts. The run needs Nate's authorization: it is a live model run on the M920q, with no training.
   - **Development run:** the 72-trial native-endpoint suite against the current corpus, graded with the existing failure codes. Comparable with the October runs.
   - **Acceptance (later):** the sealed 120 set under the custody design, after the remaining verifiers are named.
3. **A citation contract decision.** C3 adds visible section citations, so Cowork rules on two questions:
   - whether the existing `sources` list (with `REVIEWED_DOCUMENT` nodes) *is* the citation contract;
   - or whether a new optional field is added while existing fields stay unchanged.

   The rule from C1 stands: answers cite only what was delivered.
4. **A live-read boundary.** A written contract for which existing role-appropriate live reads (sensor and health) C3 may call, so static RAG is never substituted for a current reading. It uses existing endpoints only, and no actions are executed.
5. **A browser journey harness.** The exit evidence needs real HTTP and browser journeys (task completion, clarifications, recovery). The Ask panel needs a Playwright journey in the UI test suite: no new infrastructure, with the model stubbed for deterministic UI checks and the real model only in authorized live runs.
6. **A clean-room walkthrough owner.** The exit evidence requires a clean-room setup walkthrough without hidden maintainer knowledge. That is the same open "real-environment operator" role as for the sealed set, so name one person or agent for both.

### Not prerequisites (stay parked)

- C1b (pgvector or embeddings): only if measured context limits require it. At 6000 characters with 0 over budget, they do not.
- The intent and slot lanes (#420, #421), training, any new model and the GPU host purchase.

## Requested rulings

1. Part A: choose option 1 now, with option 2 scheduled after install_gap_1 (recommended), or require option 2 before #515's candidate ships.
2. Part B: accept the six prerequisites, especially #2, the authorized answer-quality run, as the gate before any C3 implementation, or amend the list.
