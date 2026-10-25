# Card attention: parallel lanes and handovers

Authority: Nate, 2026-10-24: find all related work, reconcile WSJF, orchestrate handovers to finish as fast as dependencies allow. Builds on [HO-2026-10-22-measurement-attention](HO-2026-10-22-measurement-attention.md) and [framework sections 11 to 15](../proposals/2026-10-22-measurement-attention-framework.md). **Requested assignments; acknowledgement or start is not implied.** Git is canonical. Temporary: delete when every row has an outcome link and sign-off is recorded.

## What the review changed

- **Gap found:** the framework fixes what each state means but not the borders, icon shapes, motion or any vision option. Added framework [section 15](../proposals/2026-10-22-measurement-attention-framework.md#15-attention-presentation-and-vision-accessibility-contract), UC-22, and W-109, W-110, W-111 (proposed scores, Cowork to confirm).
- **Dependency change:** W-98 is now also blocked by W-109, W-110 and W-111. The presentation lane needs no assessment data, so it runs in parallel with the data lane.
- **Arithmetic:** CoD sums and WSJF quotients for DOC-7 and W-93 to W-108 recompute correctly. No score was changed.
- **Status drift:** W-94 corrected to merged (PR #226, `8c184f7`). Still to confirm by the owner: W-107 reads `proposed` while #244 records the planning review complete on 2026-10-23 (Codex); #244's body does not yet mention #249 and #250 (Code or Codex); W-101 has partial evidence from #247 and #249 (read-only IEEE grouping, relay class from exposes), to be recorded by Code with the census, not asserted here.
- **Not scored by this change:** C7 (8.333) and C8 (3.875) stay as in the Ask backlog.

## Lanes

| Lane | Work | Owner | Machine | Start | Return evidence |
|---|---|---|---|---|---|
| Presentation | W-109 contract and mockups | Cowork drafts, Nate decides (MA-16 to MA-18), Code feasibility | any clean clone | now | completed section 15.3 matrix, mockups per theme and width, token names fixed, decisions recorded in Git |
| Presentation | W-110 vision options | Code | ilikethelights | after W-109 token names and option list | tests in both surfaces, `forced-colors` check, reduced-motion check, kiosk check |
| Presentation | W-111 theme and mode guard | Code builds, Codex reviews | ilikethelights | after W-109 thresholds | per-theme report, failing-without-tokens proof |
| Data | W-101 then W-102 and W-103 then W-96 then W-97 | Code | ilikethelights (M920q only for read-only live queries) | now, unchanged | census and caller map, first bounded PR |
| Evidence | W-104 profile curation | Cowork | any | now | per-profile disposition with primary sources |
| Evidence | W-105 oracle and protocol | Codex designs, Code runs, Cowork protocol | any | contracts now, final after W-97 and W-98 | source-based fixtures, section 15.5 cases |
| Repair | W-93 and W-108 | Code prepares, Nate operates | M920q, operator only | per section 14.2 order | installed digest, fresh finite reports, preserved load and history |
| Convergence | W-98 then W-100 then W-106 | Code, Nate | ilikethelights | after both lanes | pilot evidence, rollback drill, sign-off |

## First actions, in order

1. **Code (docs PR):** apply the accompanying patch from fresh `main` and open the PR. It allocates W-109 to W-111 and UC-22 and must merge first, so IDs are in Git before work begins.
2. **Cowork:** start W-109 on the merged IDs; confirm or amend the three proposed scores; start W-104.
3. **Nate:** rule MA-16 to MA-18 (list below), or delegate to the proxy-PO and record it.
4. **Code:** in parallel, W-101 census (no dependency on the above).
5. **Codex:** review the docs PR as proxy-PO (it edits W-98 and W-105, which Codex owns), then W-105 fixtures from the W-96 contract.

Merge order: this docs PR, then implementation PRs in the dependency order of framework 11.2. No UI PR that changes attention colors, borders or icons merges before the W-109 matrix is merged.

## Decisions waiting on Nate

- MA-16: final option list (four options, fewer, or one "accessible display" switch).
- MA-17: setting stored per browser or per signed-in person.
- MA-18: may the color-safe palette override a theme's status hues, or only where a theme supports it.

## Prompt for the Code session that applies the patch

Machine: ilikethelights (docs only, no M920q load). Freshness: `git status --porcelain` prints nothing; `git fetch origin`; `git checkout main`; `git pull --ff-only origin main`; `git checkout -b claude/card-attention-presentation-lanes`. Apply: `git am <path-to-patch>` (do not open it in an editor first). Before pushing, re-check that W-109, W-110, W-111 and UC-22 are still unallocated on `origin/main` and in every open PR (`gh pr list --state open`, then each PR's backlog diff); if a collision appears, renumber and say so on the PR. Validate: run `node .claude/skills/cabin-peer-agent-collaboration/scripts/resolve-append-tables.mjs <file> --check` for `docs/governance/backlog.md`, `docs/governance/use-cases.md` and the framework file. Push, open the PR with the governance record below, and post the PR link on [#244](https://github.com/ImpressiveLLC/FaceoftheCabin/issues/244). Do not merge without Nate's go. Tests: none apply (docs only); report that.

Governance record for the PR body:

| Field | Content |
|---|---|
| Use case | UC-22 (new), UC-21 |
| Requirement / decision | framework section 15 (proposed); MA-16 to MA-18 open |
| WSJF linkage | Ad-hoc: W-109 (12.3), W-110 (6.6), W-111 (10.3), proposed scores; W-98 and W-105 amended; W-94 status corrected |
| User value | Household members with color-vision or light sensitivity can read every attention state and adjust the display |
| System value | Presentation design leaves the W-98 critical path and runs in parallel with the data lane |
| Deviations | none |
| QA validation | table validator ok on three files; links checked; no code, so no tests; live verification no |
| Release state | not merged; documentation only |
