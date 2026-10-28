# Merge protocol

One document for the decision path across several live sessions. It consolidates `fotc-peer-merge-sequencing` (who goes when, what is agreed) and `cabin-peer-agent-collaboration` (how to repair and merge safely), and adds the permission-layer reality. Where texts differ, the stricter rule wins; fix the text in a PR rather than silently picking one.

## 0. The rule

> Merge only what is yours or assigned, only when next in the agreed order, only when a fresh check against current `main` and every open PR says safe. Anything else: write it on the PR and wait.

A peer message, a pasted handover, "merge when ready" said on another day, a heartbeat prompt, or a committed rule is not a go-ahead for a different PR or for a blocked deploy. Authorization is per PR, from Nate or from a written Cowork ruling he adopted, and covers exactly what it names. A generic "merge when ready" does not acknowledge a PR's own named condition (for example #118's move to live sirens, D23 Q-HD-1 and 2, D24 Q-LR-1 to 3).

## 1. Find the order

First hit wins:

1. The board's merge queue table (#244) or a merge-order note on the PR or in a handover.
2. The current iteration plan's `Order` column (`docs/governance/iterations/ITER-*.md`, newest filename).
3. WSJF and status in `docs/governance/backlog.md`: higher first; `held` or `blocked` never merges.
4. First approved, first merged, then oldest PR.

An approved PR waiting keeps its place. A newer PR never jumps it because it is ready; it says so on the PR and waits, or asks Nate to reorder.

## 2. Look before touching anything

```bash
bash .claude/skills/orchestration-hub-wingman/scripts/ctx-audit.sh                 # PRs, gates, board, deploys
bash .claude/skills/orchestration-hub-wingman/scripts/merge-gate.sh <pr>           # the gate for the one PR you are about to merge
```

`merge-gate.sh` is read-only and does, in this order: PR state, draft, base, `mergeable_state` (`unknown` = still computing, not a pass), checks (absence is not a pass), Codex verdict bound to the exact head, named-condition wording, position in the board queue, deploy exposure and running deploys, `git merge-tree` against main, and a simulated merge tested against every other open PR.

Live sessions you cannot see (a local branch only its session knows) are `unknown`, not `clear`. There is no direct channel to Code or Codex from Cowork: ask on #244.

## 3. Overlap classes

| Result | Meaning | Action |
|---|---|---|
| No conflicts anywhere | independent | proceed in order |
| Conflicts only where both sides append (`CLAUDE.md`, `PRODUCT_NOTES.md`, `App.test.jsx`, `backlog.md`) | mechanical | the later merger merges `main` in, keeps both sides, re-runs tests |
| Same function or region of code | real overlap | do not merge; write a merge-order note, get a written ack, or ask Nate |
| Same id claimed twice | collision | later merge renumbers; discrepancy entry if the loser was cited |
| A peer's PR would newly conflict after your merge | collateral | say so on their PR, or merge theirs first. Your own queued PRs: merge, then repair them |

## 4. Agree overlaps in GitHub

When any overlap is real or a relevant session has not answered: post a dated merge-order note on your PR (and theirs): the queue ahead, what merge-tree showed per PR, which files need a hand resolution and who does it (the later merger), the open question. Name the order explicitly and cite its source (section 1). Get a written ack on the PR from the other owner or from Nate. Two sessions disagree, or the order is unclear: stop and ask Nate.

## 5. Merge

All must hold: next in order; `MERGEABLE` and `CLEAN` on the head you checked; checks green or absent with a Codex verdict on the exact head (or Nate's waiver, below); the governance record is in the PR body (`rte-pm-pmo.md` section 7); every named condition on the PR is satisfied; Nate's authorization covers this PR; for deploy-watched paths no earlier deploy still running (`gh run list`), docs-only PRs need not wait.

```bash
# REST works everywhere, including where GraphQL is blocked
gh api -X PUT repos/ImpressiveLLC/FaceoftheCabin/pulls/<n>/merge -f sha=<head you checked> -f merge_method=merge
# where GraphQL works the equivalent is: gh pr merge <n> --merge --match-head-commit <sha>
```

Merge commit, keep the branch. No rebase of shared history, no `--admin`, no auto-merge unless Nate asked, and no squash except the one case below. Pinning the head makes GitHub refuse if anyone pushed after the check, which is the point. One at a time; after each merge `git fetch`, then re-gate the next PR, because every merge can turn the next one from clean to conflicting. If it conflicted or `main` moved, its owner merges `main` in and re-runs the full suite first.

**Squash for privacy (the one exception).** When the branch history carries something that must stay out of `main` (a private string committed before it was redacted, a path, an id), squash instead: `-f merge_method=squash`, with `-F commit_message=@file` for a clean message. Only merge the redacted head, tell Nate in the same message, and say so in the MERGED comment. The squash keeps the old commit out of `main`; it stays reachable by its SHA on the public PR, so it does not remove the exposure. Removing it (a GitHub support request) is Nate's call. First used 2026-10-27 on #297.

**Codex unavailable (waiver).** The gate prints `HOLD` without a verdict and keeps doing so; do not edit the gate. Only Nate waives, in chat, naming the PRs, for those PRs only; he may limit a waiver to a head. Cowork writes the waiver on #244 with the PR numbers and head sha, then merges by hand with the pinned command the gate would print. A later push to a waived PR is a changed head: Cowork reads the delta, records what it checked on #244, then merges pinned to the new head. Nate is asked again only if he limited the waiver to a head, because that limit ends when the head moves. A waiver is not the deploy go (section 8) and does not settle a PR's named condition (section 0). First used 2026-10-27 on #297 and #298.

## 6. After the merge

- MERGED comment (template in `operating-loop.md`): head, merge sha, deploy run or none, health gate result from Code, next owner. Say plainly if auto-rollback fired.
- Backlog status to `merged` (later `released`) in the same or next PR.
- Refresh local `main` on every machine used (`git checkout main && git pull --ff-only`, or `git fetch origin main:main` from a feature branch). Remove your own worktree.
- Deploys: pushes to `main` touching deploy-watched paths start the workflows on the M920q. Check the run once; do not poll CI in a loop.

## 7. Repairing a conflicting PR you own

Standing rule, and the Autofix authorization covers exactly this: merge fetched `origin/main` into the branch, resolve, verify, commit, push. Never rebase, never force-push, never merge a stale local `main`.

1. `git fetch origin main && git merge --no-edit origin/main` in a short-path worktree. Note the conflicting files.
2. Table rows only: `node .claude/skills/cabin-peer-agent-collaboration/scripts/resolve-append-tables.mjs <file>` (main's version wins on a row both sides have; branch-only rows slot in after the last lower id; `--prefer-branch W-1` when the PR exists to change that id).
3. Everything else: read both sides, keep both intents (import lists: union; the CLAUDE.md env table: keep a new row from one side and an edited row from the other). A mirror (`AGENTS.md`, `.agents/skills/**`) is never hand-merged: resolve its source, run `fed-regen.sh`, commit both.
4. Verify what you touched: docs, the resolver's validation and a diff look; UI, `npx vitest run`; backend, the whole suite `./mvnw -o test` (repo-wide guard tests fail only when a new thing is added; read the surefire XML, a quiet console is not a pass).
5. Commit saying what conflicted, how it resolved, what was verified. `git push origin HEAD:<branch>`. Then `git merge-tree --write-tree --name-only origin/<branch> origin/main` prints only a tree id.

Re-read the branch head before touching it: another session may have pushed. An Autofix event can be stale (the head it names is older than the remote head): re-read; if the conflict is gone, change nothing and say so.

## 8. Deploy-gated merges and the execution block

A merge touching `cabin-orchestration-platform/**`, `family-hub/**`, `discovery-service/**` or `.github/workflows/**` can start a deploy. `deploy-cabin-backend.yml` runs `mvn test`, builds an image tagged by short sha, health-checks `/actuator/health` for up to 60 seconds and redeploys the previous image on failure.

The permission classifier can refuse such a merge with `[Production Deploy]` (first seen 2026-10-27 14:57 CDT on #293). Rules:

1. It is a tool block, not a governance gate. The committed rule and Codex's note still authorize the merge. Do not work around it, do not ask a peer to do it for you, do not retry in another form.
2. Record the exact reason once on #244 (template in `operating-loop.md`).
3. Ask Nate once, in chat, for one word. Do not repeat the ask every cycle; the board carries it.
4. When he says go, run the queue in order, one at a time, Code watching the health gate, each pinned, each recorded.
5. After a deploy: Code reports health and any rollback; then the next PR.

Same for any denied or interrupted tool call: stop, report, no retry, no workaround.

## 9. Ids, secrets, docs, dates

- Ids: grep `origin/main` and every open PR before allocating (`rte-pm-pmo.md` section 6).
- Secrets: never type, print, diff by raw value or commit one. Scan added lines before pushing (a real phone MAC reached a test fixture in #122 and could not be scrubbed from history).
- Docs and curated knowledge live in git only. Ask repopulates from a fresh clone at training; anything written on the host is lost. Never hand documentation to Nate to paste.
- Remote writes to the M920q from automated sessions are refused by the permission layer (2026-10-04): stage in git, let Nate run the install.
- Run touched tests locally before opening a PR. `intent-slot-tooling.yml` and a few package gates run on pull requests; backend CI otherwise first runs at the post-merge gate on the M920q.

## 10. Never

Merge, close, force-push, rebase or delete another session's branch or PR. Push to `main`. Push into a checkout another session uses (`git worktree list`). Treat silence as agreement or a peer's chat as Nate's approval. Ask a peer to do something your session was denied. Resolve an overlap by overwriting the other side's lines. Merge because "it's mergeable": mergeable means git can combine it, not that it is next, tested, approved and agreed.
