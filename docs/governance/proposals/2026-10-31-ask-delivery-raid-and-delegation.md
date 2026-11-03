# Ask delivery: RAID log and delegation to the child bot (PMO card, parent wingman)

Date: 2026-10-31. Status: proposed; documentation only. Owner: parent wingman (Code session), as PMO. CEO and stakeholder: Nate. Facts about the child bot and its machine are session-reported by Code and not verified by Cowork unless marked.
Basis: [AI assistant WSJF backlog](../../ai-assistant/wsjf-backlog.md), [HO-2026-10-31](../handovers/HO-2026-10-31-ask-weekend-next-steps.md),
[HO-2026-10-25 training readiness](../handovers/HO-2026-10-25-claude-to-code-and-codex-training-readiness.md), W-117 and W-133 in the
[backlog](../backlog.md), and the [delivery bot design](2026-10-31-autonomous-delivery-bot-design.md). Neutral wording only: this repo is public.

## What "Ask training done and deployed" means here (so we measure the right thing)

Two different things share the word "training"; the plan treats them separately.
1. **Ask answers from the corpus** (W-117: C2 corpus, then C1 retrieval, then C3 one-stop assistance). No model training. Most of the remaining
   work is here, it is mostly tier A, and it is what a user will notice first.
2. **Intent/slot model fine-tuning** (SFT of the 3B model, POC2, W-75 "I'm cold"). It is **parked** behind item 1 by Nate's priority change
   (2026-10-26) and is blocked by human data and access, not by tooling (HO-2026-10-25): zero human-reviewed rows, sealed safety and test files held by
   Nate, gold below every threshold, no agent path to the M920q runner at that time.

Validation target for item 1: rerun the 150 phrasings and the boundary set against the **deployed** Ask and report no-category rate, wrong-category
rate and question-to-action rate. Today only the routing replica is measured (95 to 16 unrouted; in scope 79 to 0). Live answer quality is `NOT_MEASURED`.

## Lanes

| Lane | Who | May do | May not |
|---|---|---|---|
| Parent (PMO, wingman) | Code session | plan, review, decide order, run host evidence, merge tier-A PRs after an independent CLEAN, own this log | change safety or auth without Nate; run anything on the cabin host that deploys or restarts |
| Child bot | `factory` on the spike machine, rootless container (session-reported) | work GitHub issues into PRs in a sandbox repo now; later PRs on this repo from a fork or a PR-only token | merge; hold any host, Home Assistant or deploy credential; touch sealed files |
| Cowork | RTE, independent review lane | independent CLEAN or HOLD on exact heads, board, merge records | n/a |
| Nate (CEO) | decisions and approvals | tier B and C, audits (S5), sealed files, the deploy go | n/a |

## Work breakdown (the remaining plan)

| # | Item | Lane | Gate | State today |
|---|---|---|---|---|
| 1 | Merge the safety and auth PRs: device-command guard (#381), managed sessions (#378) | Parent + Cowork review; Nate go | independent CLEAN on the exact head | done 2026-10-31: both independently reviewed CLEAN, merged (f034eba, cd7f564) and deployed green |
| 2 | Deploy and live-measure Ask routing: 150 phrasings plus boundary set against the deployed Ask | Parent (needs an administrator session) | #1 not required; needs a session to `/api/helpdesk/ask` | `NOT_MEASURED` |
| 3 | Close routing gaps: the 16 unrouted phrasings and the three unmapped guide sections | Child (tier A) with parent review | child go-live checklist below | not started |
| 4 | Corpus completion to the frozen inventory (C2): remaining guides, each section mapped and reviewed | Child drafts, parent verifies against source, Cowork reviews | same | partly done (alerts, devices, workflows, access, storage guides merged) |
| 5 | Retrieval evaluation (C1): graded rounds, variance floor (C6), conditional pgvector decision | Parent designs; child runs offline tooling | M920q access for live runs | 150-phrasing replica only |
| 6 | One-stop assistance (C3): in-app explain, sources, troubleshoot, no dead ends | Parent + child, UI tier A | independent CLEAN | not started |
| 7 | Intent/slot SFT (POC2): author S2 families, review packets, freeze, run, export | Child may draft families; reviews and sealed files are Nate and Cowork | Nate S5 audits at 90 percent or better; sealed files placed; M920q idle | blocked |
| 8 | "I'm cold" vertical POC (W-75) | Nate decision; code tier B | #381 merged, W-66 and W-67, shadow report, Nate go | flag off, no executor, none authorised |
| 9 | Delivery bot spike (PR #380) | Parent | the pass criteria in the design | session-reported: running, first PR produced unattended (not verified by Cowork) |

## RAID

**Risks**
- R1 Review bottleneck: independent reviews were silent for hours on 2026-10-31; safety PRs wait. Mitigation: Cowork priority order, nudge cadence, tiered merge for low-risk PRs within Nate's recorded instruction (see A1).
- R2 Reviewer independence: the child's reviewer is Claude, not a second vendor (session-reported: no Codex login for the child yet; Gemini CLI is not a supported agent). A same-family reviewer misses same-family errors. Mitigation: parent plus Cowork review remain the gate for anything beyond tier A.
- R3 Public repository: details of security findings must never be committed; one such slip already happened and was cleaned. Mitigation: neutral-wording rule, private hand-over channel, scan before push.
- R4 Unsandboxed agents: the child runs with sandbox checks off; the container is the only boundary. It holds a Claude login and a repo-scoped token. Mitigation: no host, Home Assistant or deploy credentials in the volume; short-lived token; delete the volume to wipe.
- R5 Cost: about 0.5 USD per agent run on Nate's Claude account; one issue used six runs before it worked. Mitigation: per-issue run cap (50) lowered for routine work; report cost per merged PR.
- R6 Host availability: the spike machine is a laptop with no remote wake, and remote access to the cabin host needs periodic re-authentication, which stalled host checks for hours. Mitigation: keep the spike machine powered and awake; lengthen key expiry.
- R7 Model quality: the 3B model is the answer-quality limit (Nate decision pending on a stronger model). Mitigation: measure first; decide with data.
- R8 Windows shell pitfalls (leading slash rewritten, heredoc quoting) silently dropped commands. Mitigation: post commands from files; read back.

**Assumptions**
- A1 Recorded instruction (Nate, 2026-10-31 02:41 CDT, on a CLEAN pull request): "auto-merge next time if reasonable", read as: merge a CLEAN PR that sits within an existing go. Nate's earlier batch go (2026-10-31 00:43 CDT) covered one named batch of deploys in order. **No standing deploy policy is ratified.** Any wider reading (merge on CLEAN for deploy-triggering paths without a per-PR or per-batch go) is a question for Nate, below. Tier B and C stay with Nate.
- A2 Claude headless on Nate's subscription is the model for the child; local Ollama is only for cheap steps and is not yet installed on the spike machine.
- A3 The corpus-first order (C2, C1, C3) stays; SFT stays parked until data and access unblock.

**Issues (now)**
- I1 (closed 2026-10-31) #381 and #378 were reviewed CLEAN, merged and deployed.
- I2 No administrator session for live Ask measurement.
- I3 Child agents sometimes rebase or ask a question that parks an issue (one issue closed after that).
- I4 Branch protection, rulesets and CODEOWNERS are **not in place** on this repo today (main is unprotected), and the bot and Nate would share one GitHub identity, so CODEOWNERS could not tell them apart. Tier B is enforced by process until Nate decides otherwise. The paid-plan limit does not apply: this repo is public.

**Dependencies**
- D1 Spike pass criteria met (design doc) before the child touches this repo.
- D2 A PR-only credential for this repo (fork or fine-grained token without merge or admin).
- D3 Nate: sealed files, S5 audits, deploy go; Cowork: reviews.

## Child go-live checklist (before it works on this repo)

1. Spike passes: two issues reach review unattended (one done), the reviewer rejects a seeded defect, the tier-B change cannot merge without a human, restart from the clone alone.
2. Credential is PR-only on a fork or a fine-grained token with no merge, no admin, no secrets scope; stored only in the container volume.
3. Issue labels carry the tier; the merge actor acts only on tier A with an independent CLEAN on the exact head.
4. Every child PR states what was run and what was `NOT_RUN`; a child review never counts as the independent CLEAN.
5. Parent reviews the first five child PRs line by line before any are merged without it.

## Decisions needed from Nate

1. Approve the child go-live checklist and its first assignment (items 3 and 4 above, tier A only: docs, tests and backlog rows).
   1b. Decide whether tier A may later include deploy-triggering paths (Ask content and routing, UI polish, `backend/**`, `ui/**`, `infra/**`) and, if so, what go is needed per PR or per batch.
2. Provide an administrator session (or run the live measurement) for item 2.
3. Confirm SFT stays parked until item 2 shows what the corpus path leaves unanswered.
