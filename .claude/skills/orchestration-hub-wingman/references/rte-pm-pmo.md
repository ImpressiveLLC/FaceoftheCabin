# RTE, PM and PMO: roles, decision rights, scoring, ids, trace

Read when deciding who decides, scoring an item, allocating an id, tracing work or checking what "done" needs. Owners of the underlying rules: `docs/governance/README.md`, `docs/governance/definition-of-done.md`, `docs/governance/rte/schedule.md`.

## 1. Who decides what

| Decision | Nate | Cowork | Code | Codex |
|---|---|---|---|---|
| Direction, priority order, the goal | decides | proposes, sequences inside it | follows | follows |
| Rulings on questions inside a ratified decision | final authority | rules, records | asks | asks |
| New decision (D-n) | ratifies | drafts | proposes by PR | proposes by PR |
| WSJF scores | ratifies | scores (proxy-PO, marked `proposed`) | proposes ad-hoc with a score | proposes with a score |
| Merge of a Cowork PR | n/a | merges pinned | n/a | verifies if asked |
| Merge of a Code PR | go for anything deploying | merges when Codex CLEAN on the head and nothing named blocks it | may merge its own PR under the peer-collaboration conditions; a deploying merge waits for Nate's in-session go because the permission layer enforces it | CLEAN or HOLD only |
| Live device write, secret, sealed data, irreversible action | decides, alone | prepares the decision, stops | stops | stops |
| Host installs and remote host writes | runs them | stages in git | stages in git | n/a |
| Acceptance of a measured result | final | ratifies the grading | supplies evidence | grades independently |
| Cover for an absent peer lane | orders it in chat, naming lane and period; may veto it | stands in through independent agents under a `CODEX-PROXY` label, spot-checks, adjudicates; never a deploy go | n/a | on return, confirms or overrules each stand-in verdict |

Cowork never reverses a decision Nate made, never ratifies on his behalf in a way that cannot be undone, never asks a peer to do what Cowork itself was denied.

## 2. RTE duties (flow)

Keep one live board. Name one owner and one ready-signal per item. Run the cycle (`operating-loop.md`). Enforce SLAs: ack 20, Codex verdict 30, Cowork review 20, merge 10 after CLEAN. Merge in the agreed order, one at a time. Nudge at +40, escalate at +60 on the critical path. Re-route when an owner is unavailable. Keep the heartbeat armed. Record every merge, verdict and rerun with ids, counts, hashes. When a peer is out and Nate orders cover, run the stand-in (`stand-in-role.md`) and size any pattern dig by `pattern-mining.md` section 2.

## 3. PM duties (value)

Hold the goal. Today (W-117, Nate 2026-10-26): Ask answers configuration and maintenance questions from the corpus. Order: C2 corpus, C1 evaluated retrieval, C3 one-stop Ask. Parked behind it, nothing deleted: POC2 `express_need`, intent/slot polish, model training, routing. Exception that stays live: the question-versus-command boundary (configuration questions reach `answer_question`, never treated as commands).

Turn each result into the next ask: ratify, name what failed, name the domain or map entry to fix, hand a fix list to Code, schedule the re-run.

## 4. PMO duties (portfolio)

- `docs/governance/backlog.md` is the portfolio. One row per work item, 11 columns: ID, Work, UC, Traces to, Origin, Owner, CoD, Size, WSJF, Status, Blocked by. Status words: proposed, ready, in progress, in review, merged, released, held, gated, dropped.
- The Ask track keeps `docs/ai-assistant/wsjf-backlog.md` (Cowork owns and scores; Codex supplies technical evaluation).
- Status of every item changes in the same PR that changes the work: `in review` when opened, `merged` after merge, `released` after live verification.
- Dashboard from the backlog: `node .claude/skills/orchestration-hub-wingman/scripts/pmo-build.mjs --check` then `--out`. Publish to the PMO artifact. Daily and at each plan change.
- Append-table edits (backlog, discrepancy log, decision index): resolver `node .claude/skills/cabin-peer-agent-collaboration/scripts/resolve-append-tables.mjs --check` before pushing.

## 5. WSJF

WSJF = CoD / Size, CoD = SafetyValue x 2 + FamilyUsability + PlatformExtensibility + TimeSensitivity. Scores Cowork assigns under Nate's delegation are `proposed` until he ratifies. Rank does not bypass dependencies, and a safety-protecting item can outrank a higher score when it has no dependency (the W-11 first-build-order precedent).

## 6. Id allocation

Next free id = one past the highest on `main` or in any open PR. Check both, REST version (GraphQL may be blocked):

```bash
git fetch origin
git show origin/main:docs/governance/backlog.md | grep -oE '^\| W-[0-9]+' | sort -t- -k2 -n | tail -1
for pr in $(gh api "repos/ImpressiveLLC/FaceoftheCabin/pulls?state=open&per_page=50" --jq '.[].number'); do
  echo "#$pr: $(gh api "repos/ImpressiveLLC/FaceoftheCabin/pulls/$pr/files?per_page=100" --jq '.[].filename' | grep -c backlog.md) backlog touch(es)"
done
```

Swap the prefix and file for D, UC, DL. Rules: never reuse; cite only allocated ids; the later merge renumbers on collision (log a discrepancy entry if the losing id was cited); a letter suffix only splits an existing item.

## 7. Trace and record

`UC-n` (use-cases.md) to `D-n` or `R-ID` (decisions/) to `W-n` or `DOC-n` (backlog.md) to PR to `SO-date-rN` (signoffs/) to `ITER-date` (iterations/). Link by id, never by repeated prose. One fact, one home.

Every PR body carries the governance record:

| Field | Content |
|---|---|
| Use case | UC id(s), or a new UC in one sentence added to use-cases.md |
| Requirement or decision | D-number, R-ID, or "none, new" |
| WSJF linkage | planned item id with source, or "Ad-hoc" with backlog id and score |
| User value | one sentence |
| System value | one sentence |
| Deviations | discrepancy-log ids, or "none" |
| QA validation | test names and green count; live verification yes or no with evidence; denial tests where auth is touched |
| Release state | merged, deployed, live-verified, each with its own date |

Iteration-done (safe to merge) is not Product-done (released). A sign-off names the level. Handovers (`docs/governance/handovers/HO-YYYY-MM-DD.md`) are temporary: deleted when the last task closes; the durable fact moves to its one home.

## 8. Evidence rules

- Ids, counts, hashes in comments and docs. Never private text, secrets, MACs, addresses, sealed rows, private labels, raw model answers.
- Unrun gates stay `NOT_RUN` or `NOT_MEASURED`, `release_eligible: false`. Offline success establishes neither deployment nor runtime safety.
- Dates come from the clock and from `git log` or REST, not from memory. Name days with no activity.
- Distinguish the four states (local verification, remote evaluation, merge, deployment) in every status.

## 9. Gates only Nate opens

Explicit go for a deploy merge the permission layer blocks. A PR's own named condition (for example #118's move to live sirens). Sealed-file placement and review time. Host installs. The ntfy topic (W-51). Security files #118 and #122. The `rotate-secrets` check after D25. Naming the other four Home cameras. Checkout of any cart. Ordering a stand-in for an absent peer lane. A T3 pattern dig (`pattern-mining.md`). No training, export, routing or ingestion without him.
