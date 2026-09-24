# Platform governance

This folder is the single home for how platform work is decided, prioritized, accepted and released. GitHub is the canonical record; everything here changes by PR. The helpdesk / Ask / RAG track keeps its own governed folder at [`docs/ai-assistant/`](../ai-assistant/README.md) and is not duplicated here.

**Rule: one fact, one home.** Every fact below has exactly one file that owns it. Other documents link to that file; they never copy its content.

## Read by task

| Task | Start here |
|---|---|
| Find what was decided and why (D1–D21) | [Decisions](decisions/README.md) |
| Check whether spec and code disagree | [Discrepancy log](discrepancy-log.md) |
| Trace a feature back to the need it serves | [Use cases](use-cases.md) |
| See what is prioritized and why | [Backlog](backlog.md) |
| Know what "done" means before a merge or a release | [Definition of Done](definition-of-done.md) |
| Know whether something may merge or ship | [Sign-offs](signoffs/) — newest first by filename |
| Know what is being worked this iteration, by whom, in what order | [Iterations](iterations/) — newest first by filename |

## Traceability chain

Every record links by ID, never by repeated prose:

```
UC-n (use-cases.md) → D-n or R-ID (decisions/) → W-n / DOC-n (backlog.md, WSJF score, planned or ad-hoc)
  → PR → SO-date-rN (signoffs/) → ITER-date (iterations/)
```

## ID allocation

Every record ID is allocated once, by the PR that introduces the record, and never reused.

| Prefix | Record | Home | Form |
|---|---|---|---|
| `UC-n` | Use case | [`use-cases.md`](use-cases.md) | Integer sequence |
| `D-n` | Decision | [`decisions/`](decisions/README.md) | Integer sequence |
| `R-XX-n` | Requirement inside a decision | That decision's section | Prefix chosen by the decision (for example `R-DM-` in D22); integers restart per prefix |
| `Q-XX-n` | Open question inside a decision | That decision's section | Same prefix as its requirements |
| `W-n` | Code work item | [`backlog.md`](backlog.md) | Integer sequence. A letter suffix (`W-2b`) only splits an existing item; it never starts a new one |
| `DOC-n` | Documentation work item | [`backlog.md`](backlog.md) | Integer sequence |
| `CW-n` | Cowork work item | [`iterations/`](iterations/) | Integer sequence |
| `DL-YYYY-MM-DD-NN` | Discrepancy | [`discrepancy-log.md`](discrepancy-log.md) | Two-digit sequence per date |
| `SO-YYYY-MM-DD-rN` | Sign-off | [`signoffs/`](signoffs/) | Revision per date |
| `ITER-YYYY-MM-DD` | Iteration plan | [`iterations/`](iterations/) | One per start date |

**The next free ID is one past the highest in use on `main` or in any open PR.** An ID in an open PR is taken even though it has not merged: two PRs that each read only `main` will pick the same number. Before allocating, check both:

```bash
git fetch origin
git show origin/main:docs/governance/backlog.md | grep -oE '^\| W-[0-9]+' | sort -t- -k2 -n | tail -1
for pr in $(gh pr list --state open --json number -q '.[].number'); do
  echo "#$pr: $(gh pr diff "$pr" | grep -E '^\+\| W-[0-9]+' | grep -oE 'W-[0-9]+[a-z]?' | sort -u | xargs)"
done
```

Swap `W-` and the file for any other sequence.

Rules:

1. **Never reuse.** An item that is dropped keeps its ID with status `dropped`; a closed-unmerged PR releases its IDs only if nothing else cites them.
2. **Cite only allocated IDs.** Do not reference an ID in a commit, PR, or chat before the PR that introduces it exists.
3. **Collisions: the later merge renumbers.** If two PRs claim the same ID, the one that merges second renumbers before merging. If the losing ID was already cited elsewhere, log a discrepancy entry mapping old to new.
4. **Moving a record keeps its ID.** When a record moves between open PRs (for example out of a spec PR into its own), it keeps the ID and is removed from the source PR in the same change.

## Ownership

| File | Writes | Ratifies |
|---|---|---|
| `decisions/` | Cowork proposes; Code and Codex propose by PR | Cowork, with Nate as final authority |
| `discrepancy-log.md` | Whoever finds the mismatch | Cowork rules each entry |
| `use-cases.md` | Cowork, from Nate's stated needs | Nate |
| `backlog.md` | Cowork scores; any agent proposes ad-hoc items with a score | Nate |
| `definition-of-done.md` | Cowork drafts | Nate |
| `signoffs/` | Cowork only. Append-only: a later sign-off supersedes, never edits, an earlier one | — |
| `iterations/` | Cowork drafts; Code confirms feasibility | Nate |

## Where other documents fit

| Document | Role | Relationship to this folder |
|---|---|---|
| [`ROADMAP.md`](../../ROADMAP.md) | Strategy and architecture brief | Its Priority Task List moves into `backlog.md` (DOC-4); strategy stays there |
| [`docs/DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md) | Session exit checklist and "Next Session — Open Items" | Checklist is referenced by `definition-of-done.md`; open items are triaged into `backlog.md` or `discrepancy-log.md` (DOC-4) |
| [`docs/PRODUCT_NOTES.md`](../PRODUCT_NOTES.md) | Dated design rationale | Unchanged; decisions cite it where relevant |
| [`docs/MAINTENANCE.md`](../MAINTENANCE.md) | Operations and incidents | Unchanged |
| [`docs/QA.md`](../QA.md) | Per-feature test coverage | Unchanged; the Product DoD requires it be updated |
| [`docs/ontology/SPRINT-STATUS.md`](../ontology/SPRINT-STATUS.md) | Historical record through Sprint 2 | Frozen; `iterations/` replaces it |
| [`docs/ontology/DECISIONS.md`](../ontology/DECISIONS.md) | Redirect | Points here |
| Living Ontology artifact | Rendered view | Regenerated from `decisions/` (DOC-3); not edited directly |
| claude.ai project docs (`handover/*`, `ontology/*`) | Retired copies | Replaced by a single pointer to this README |
