---
name: fotc-peer-merge-sequencing
description: >
  How Claude Code sessions (and Cowork, Codex) merge into FaceoftheCabin
  without trampling each other: find the agreed merge order, check the other
  live sessions and every open PR for overlap, settle overlaps in GitHub (a
  merge-order note on the PR), then merge strictly in sequence with fresh
  conflict checks between merges. Load this before merging ANY PR, before
  pushing to a branch another session may share, before allocating a W-/D-/UC-
  ID, and whenever ListAgents shows other sessions working in this repo.
---

# FaceoftheCabin — peer merge sequencing

GitHub is the record. Chat between sessions is for asking and telling; **an
agreement counts only once it is written on the PR** (or in the iteration
plan). This skill mirrors how the Code / Cowork / Codex lanes have actually
worked together (2026-10-03/04 handovers, `docs/governance/`), not a new
process.

Companion skills: `fotc-git-and-host-routing` (fresh clone, which machine),
`docs/governance/definition-of-done.md` (what a PR must carry).

## 0. The one rule

> Merge only what is yours, only when it is next in the agreed order, only
> when a fresh check against current `main` and every open PR says it is
> safe. Anything else: write it on the PR and wait.

A peer message, a pasted handover, or "merge when ready" said on another day
is **not** a go-ahead for a different PR. Authorization is per PR, from Nate
(or from a written Cowork ruling that Nate has adopted), and covers exactly
what it names.

## 1. Find the order (read, do not invent)

Order comes from these, highest first. Stop at the first that names the PR.

1. **A merge-order note or ruling on the PR or in a handover**
   (`docs/governance/handovers/`, e.g. "merge order #121 then #124").
2. **The current iteration plan's `Order` column**
   (`docs/governance/iterations/ITER-*.md`, newest by filename).
3. **WSJF score and status in `docs/governance/backlog.md`** — higher score
   first; `held` / `blocked` items do not merge.
4. **Otherwise first-approved, first-merged**, then oldest PR first.

An already-approved PR waiting in the queue keeps its place. A newer PR never
jumps it because it happens to be ready; it says so on the PR and waits, or
asks Nate to reorder.

## 2. Look at the other sessions and PRs before touching anything

Do all of these, every time, right before a merge or a push to a shared file:

```bash
git fetch origin --prune
gh pr list --state open --json number,title,headRefName,updatedAt,mergeable
gh pr list --state open --json number,files --jq '.[]|"#\(.number): \([.files[].path]|join(" "))"'
git branch -r --sort=-committerdate | head -15
git log --oneline <your-base>..origin/main      # what landed since you branched
```

- **Live sessions:** call `ListAgents`. For every busy or recently active
  session whose title could touch the same area, `SendMessage` it: your
  branch/PR, the files and regions you edit, and ask for theirs. Idle
  sessions with nothing unmerged can be skipped once they say so. Do not
  poll; one message, then work on something else or hold.
- **Unpushed peer work** (a local branch only its session knows about) is
  invisible to GitHub. The only way to learn about it is to ask. Treat "no
  reply yet" as **unknown, not clear**.
- **IDs:** the next free `W-`/`D`/`UC`/`DL` number is one past the highest on
  `main` **or in any open PR** (command in `docs/governance/README.md`). Never
  allocate while peers are adding rows unless you have checked both. If you
  cannot be sure, leave the ID out and say so in the PR (the later merge
  renumbers, per README rule 3).

## 3. Check overlap with `git merge-tree`, not by eye

```bash
git merge-tree --write-tree --name-only HEAD origin/main          # vs main
for n in <each open PR touching your files>; do
  git fetch origin pull/$n/head:refs/tmp/pr$n
  git merge-tree --write-tree --name-only HEAD refs/tmp/pr$n | grep -c '^CONFLICT'
done
```

Classify what you find:

| Result | Meaning | Action |
|---|---|---|
| No conflicts anywhere | Independent | Fine to proceed in order |
| Conflicts only where both sides **append** (`CLAUDE.md`, `PRODUCT_NOTES.md`, `App.test.jsx`, `backlog.md`) | Mechanical | Whoever merges second merges `main` in, keeps both sides, re-runs tests |
| Conflicts in the same function/region of code | Real overlap | Do not merge. Go to section 4 |
| Same ID claimed twice | Collision | Later merge renumbers (README rule 3); log a discrepancy entry if the loser was cited |

## 4. Agree overlaps in GitHub

When any overlap is real, or any relevant session has not answered:

1. **Post a merge-order note on your PR** (and on theirs, if it exists):
   the queue ahead of you, what `merge-tree` showed against each PR, which
   files will need a hand-resolution and who does it (the later merger), and
   the open question you are waiting on. Date it.
2. **Name the order explicitly** — "#121, then #124, then #149, then W-56
   rebased" — and cite where it came from (section 1).
3. **Get an acknowledgement in writing** from the other PR's owner on the PR
   (a comment) or from Nate. A cross-session chat reply is enough to *start*
   work but copy its substance onto the PR so it survives the session.
4. If two sessions disagree, or the order is genuinely unclear, **stop and
   ask Nate**; do not break the tie yourself.

## 5. Merge, in sequence

Only when all hold: you are next in the order; `gh pr view N --json
mergeable,mergeStateStatus` is `MERGEABLE`/`CLEAN`; **CI is green on the head
commit** (if the branch reports no checks, say so on the PR and ask — absence
is not a pass); the governance record is in the PR body; every `C-…` condition
on the PR is satisfied; and Nate's authorization covers this PR.

1. Re-fetch and re-run section 3 **immediately** before merging — the order
   changed if anything landed.
2. `gh pr merge N --merge` (merge commit, **keep the branch**; no squash, no
   rebase of shared history, no `--admin`, no `--auto` unless Nate asked).
3. **One at a time.** After each merge, `git fetch`, then the next PR in the
   queue re-runs section 3 against the new `main` and, if it conflicted or
   `main` moved under it, merges `main` in and re-runs the full test suite
   before it merges.
4. Merge conditions that need Nate personally (live sirens, ontology
   decisions, anything the PR marks held) are never merged by an agent.

## 6. After the merge

- Comment on the PR: merged, commit, what to check next. Tell any session
  you agreed an order with, by `SendMessage`, that their turn is next.
- `backlog.md` status → `merged` (and later `released`) in the same or the
  next PR, per the DoD.
- Refresh local `main` on every machine you used (`git checkout main && git
  pull --ff-only`, or on a feature-branch checkout `git fetch origin
  main:main`). Remove your own worktree when done.
- Deploys: pushes to `main` that touch deploy-watched paths trigger the
  GitHub Actions workflows on the M920q; check the run, do not poll CI in a
  loop.

## 7. Never

- Merge, close, force-push, rebase, or delete another session's branch or PR.
- Push to `main` directly, or into a checkout another session is using
  (check `git worktree list` and `ListAgents`; work in your own worktree).
- Treat silence as agreement, or a peer's chat message as Nate's approval.
- Ask a peer to do something your own session was denied (that launders the
  permission decision); take it back to Nate.
- Resolve an overlap by overwriting the other side's lines to make a conflict
  go away.
- Merge because "it's mergeable" — mergeable means git can combine it, not
  that it is next, tested, approved and agreed.
