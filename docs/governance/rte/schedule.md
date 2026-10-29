# RTE schedule and operating rules (Cowork, RTE and PM)

Source of truth for who acts, when and how, in two layers. This file is the committed copy of the rules and the plan, refreshed by Cowork at each change of plan; it stays authoritative for both until a docs PR changes it. Live status (who holds which item now, current PR heads, verdicts, deadlines in flight) is the edited comment on [#244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244#issuecomment-6449313109). If the two disagree about live status, the board is right. If the board states a rule or plan change that this file does not, treat it as a proposal until a docs PR merges it. The dated snapshot below (2026-10-27 18:19 CDT) is a historical record: its states and deadlines are as of that time and never override the board.

How a session uses it: read the board first, then this file for the rules, the [context registry](context-registry.md) for where things live, and the [Wingman skill](../../../.claude/skills/orchestration-hub-wingman/SKILL.md) to run the loop.

### Operating rules (effective now)
1. **Handoff = a comment that starts with `ACTION <who> by <time CDT>:`** plus the exact link, the ready-signal and what to post back. Anything not addressed this way is not a handoff.
2. **Listening:** Code and Codex poll GitHub issue #244 and the PRs they own every 20 minutes while a handoff to them is open. Cowork polls every 15 to 30 minutes (session heartbeat) and on every GitHub event. Direct nudge to Code or Codex only when an item is 40 minutes past its time.
3. **Ack in 20 minutes** (one line: "got it, ETA hh:mm"). No ack by +40 minutes: Cowork nudges directly and re-routes if the owner is unavailable.
4. **Review SLA:** Codex verdict within 30 minutes of a head being posted; Cowork source review within 20 minutes. A verdict binds to the head it names: `CLEAN <sha>` or `HOLD <reasons>`.
5. **Merge SLA:** a PR that is CLEAN on its pinned head, green and mergeable is merged by Cowork within 10 minutes, one at a time, pinned to the head SHA, subject to rule 8. A PR with a `backend/**` change also needs Code to watch the deploy gate. Stop and call Nate only for: a failed deploy that did not roll back, a sealed-data or secret exposure, a live device write, anything irreversible.
6. **Record:** every merge, verdict and rerun result is a comment with ids, counts and hashes only, never private text. Cowork keeps the board and `docs/governance/backlog.md` current.
7. **Notify back:** the owner posts `DONE <item> <link>` and the next owner is named in the same comment.
8. **Execution block:** a merge that triggers a deploy-watched path (`cabin-orchestration-platform/**`, `family-hub/**`, `discovery-service/**`, `.github/workflows/**`) can be refused by the permission classifier (`[Production Deploy]`) until Nate gives an in-session go. That is tooling, not governance, and the committed merge rule still authorizes the merge; Cowork does not work around it. Cowork records the reason once on #244, asks Nate for one word, then runs the queue in order. A peer comment, a heartbeat prompt or a committed rule is not Nate's go. Docs and skills only merges do not deploy and are not blocked.
9. **Codex out:** a Codex verdict is waived only when Nate says so in chat and names the PRs, and only for those PRs. Every other PR still needs `CLEAN <head>`. Cowork records the waiver on #244 with the PR numbers and does not stretch it to a later PR. When Nate has ordered a stand-in (rule 10), an independent `CODEX-PROXY` CLEAN on the exact head counts as that CLEAN; a self-review by Cowork under its own header never does.
10. **Stand-in for an absent peer:** when Nate orders it in chat, naming the lane and the period, Cowork covers that lane through independent fresh-context agents and posts their reports verbatim under a stand-in label (`CODEX-PROXY hh:mm CDT:` for Codex, never the plain peer header and never a verdict Cowork wrote itself) after spot-checking them. First instance: Codex offline until Saturday 31 Oct 2026 (Nate, 29 Oct, 14:52 CDT, chat: cover Codex's role, mirroring its earlier responses, level of engagement, documentation, positioning, research and due diligence). A stand-in verdict counts as the peer's only within existing limits: rules 3 and 4 bind it; it never widens what Cowork may merge (no deploy-watched path and no Deploy-to-host path without Nate's go, and any merge still needs a CLEAN on the exact head or a rule 9 waiver naming it); it is not a waiver of a PR's named condition; Nate can veto it at any time; and the peer's own verdicts take precedence when it returns, when it confirms or overrules each stand-in verdict. The method, templates and return audit are in the Wingman skill (`references/stand-in-role.md`); how deep to dig for patterns is bounded by time and tokens in `references/pattern-mining.md` section 2.

### The C2 loop (the training-focus path; the shape is durable, states are in the snapshot)
| # | Step | Who acts | Ready signal |
|---|---|---|---|
| 1 | Attest the deployed state (context map sha256 equals git, image, per-category chars) | CODE | `DONE attestation` with hashes |
| 2 | Start the 72-trial run | COWORK | `START C2-RUN-n` comment |
| 3 | Host run, read-only, per `run-spec.json` | CODE | raw-run sha256 and 72/72 completeness |
| 4 | Independent grading under the pinned oracle and rubric | CODEX | graded sidecar hash, PASS count, 24-question table, flags |
| 5 | Ratify, compare to the reference, name the failing questions and the domain or map entry to fix | COWORK | ratification comment and backlog update |
| 6 | Fix loop: rewrite spec (COWORK), edit PR (CODE), verify (CODEX), pinned merge, deploy and attestation (COWORK, CODE), next run (CODE), grade (CODEX), adjudicate (COWORK) | all three | new run id and record in `docs/ai-assistant/rag/eval-results/` |

### Snapshot 2026-10-27 18:19 CDT
**State.** Merged: #291, #293 and #295 (each deployed with a green health gate), #297 (Wingman skill and context registry) and #298 (C2-RUN-1 record); main is bfab467. C2-RUN-1 is ratified: 12/72 against the 11/72 reference, flat, acceptance HOLD. Codex is out until 20:22 CDT (Nate, 17:32 CDT). Nate waived the Codex verdict for #297 and #298 only (17:26 CDT); every other PR still needs its Codex CLEAN.

| PR | What | Head | State | Next |
|---|---|---|---|---|
| #299 | C2 rewrite spec r1 and held-out evaluation design (docs only) | 1e3a48a | open, clean, no verdict | Codex verdict by 21:50 CDT, after the held-out hash |
| #300 | C2 rewrite edits r1 (10 files; the context map changes, so merging deploys the backend) | 2c200f1 | open, clean, backend check green, Cowork review PASS | Codex CLEAN on this head, Code's baseline run, Nate's in-session go, pinned merge, `DEPLOY OK` |
| #301 | Purchase record for the 2026-10-27 carts (docs only) | 1dd7326 | open, clean | Codex verdict by 22:30 CDT |

**Held-out evaluation (step 6 of the loop).** Codex writes the held-out set (22 paraphrases of the 11 target questions plus 6 unanswerable probes: 28 prompts, 3 repeats, 84 trials per side) from the frozen questions and public expected text only, and posts its sha256 on #244 before it reads #299 section 4 or #300. The prompts are revealed after #300 is CLEAN, so any edit Codex asks for is made without sight of them (Cowork ruling 17:35 CDT, Code's option a). Code's runner dry run on two dummy prompts is done (17:47 CDT, wrapper sha256 prefix 56e39c11). Then: baseline against the pre-edit corpus (deployed at 2803b3d, map prefix 41056722), pinned merge of #300 on Nate's go, `DEPLOY OK <sha>` with the new map prefix, C2-RUN-2 (72 frozen trials) plus the treatment held-out run, Codex grading under rubric r2, Cowork adjudication, and a RUN-2 record in `docs/ai-assistant/rag/eval-results/` that lists the corpus delta (K19 #293, K05 #295, #300) and the map hash before and after. The decision rule is in #299 section 8; acceptance stays HOLD whatever the result.

**Times (CDT).** Cowork re-hands Codex the queue at 20:22 to 20:25: ack 20:42, held-out hash 21:00, verdicts on #299 and #300 by 21:50, #301 by 22:30.

**Cowork next.** This schedule refresh (a docs PR), the W-119 mirror repair together with a follow-up to the Wingman skill (record the squash-for-privacy exception, the Codex-out waiver rule and the watch pattern), and a final report to Nate.

**Queue behind it (not started).** K14, Batch 2, the "say you do not know" prompt instruction and any model change stay parked behind RUN-2 (spec section 8). K17 stays gated on G07 (role-filtered projection and denial tests). W-101b apply is a separate later step with per-row Nate approval; #290 and #291, its prerequisites, are merged.

### Nate-only, parked
Checkout of the two carts Cowork built on 2026-10-27 (nothing purchased; record in PR #301, `docs/governance/rte/purchases-2026-10-27.md` once merged), the answers to the open questions in the Wingman registry and `vault-and-keys.md` Part B6, sealed-file placement and S5 review time (training data), host installs, ntfy topic (W-51), security files #118 and #122, rotate-secrets check 1 Nov 06:00 UTC, which of the other four Home cameras exist (blocks W-59 seed removal only), a fresh in-session go for any deploy-triggering merge outside an agreed list.
