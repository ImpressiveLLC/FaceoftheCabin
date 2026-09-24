# Definition of Done

**Status: proposed 2026-09-23 — takes effect when Nate approves this file's PR.** Until then, [`docs/DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md) §1–10 remain the only checklist.

Two levels. An item can be **Iteration-done** (safe to merge) without being **Product-done** (released to its users). A sign-off names which level it approves.

## DoD-Iteration — required before any merge

Every PR, planned or ad-hoc.

1. **Governance record** filled in the PR body (table below).
2. **CI green** on the head commit that will be merged.
3. **Session checklist** in [`docs/DEFINITION_OF_DONE.md`](../DEFINITION_OF_DONE.md) §1–10 satisfied. That file owns those ten items; this file does not restate them.
4. **Tests for new logic**: each new behavior has at least one test that fails without the change. Test names and green count are in the PR.
5. **Deviations logged**: each spec-vs-implementation difference has a `discrepancy-log.md` entry, or the PR states "no deviations."
6. **Auth-touching changes** include denial tests for the roles and tokens the change affects.
7. **No production configuration change** (Frigate, Home Assistant, Zigbee2MQTT, compose, retention) merges without a stated before/after measurement or rollback plan in the PR.
8. **Backlog updated**: the item's status in [`backlog.md`](backlog.md) moves to `in review`, and to `merged` after merge.

## DoD-Product Enhancement — required before a capability is called released

Applies when a capability reaches the people named in its UC.

1. **All DoD-Iteration items** met for every PR in the capability.
2. **Live-verified on M920q**: exercised against the running instance by the actor type in the UC (for example, a logged-out browser for a guest link). Evidence (steps and observed result) is in the PR or sign-off.
3. **User-facing docs updated**: [`docs/USER_GUIDE.md`](../USER_GUIDE.md) or the relevant `docs/ai-assistant/user-guide/` page.
4. **QA updated**: [`docs/QA.md`](../QA.md) lists automated coverage and the manual checklist for the capability.
5. **Decision current**: the D-record matches the shipped behavior; any open discrepancy for the capability is ruled.
6. **Sign-off issued**: a `signoffs/` record approves release, citing the UC and backlog IDs.
7. **Backlog status** moves to `released`.

## Governance record required on every PR

| Field | Content |
|---|---|
| Use case | UC-ID(s) from [`use-cases.md`](use-cases.md), or a new UC stated in one sentence and added there |
| Requirement / decision | D-number, R-ID, or "none — new" |
| WSJF linkage | Planned item ID with its source, or "Ad-hoc" with its backlog ID and score |
| User value | One sentence: what the user can now do |
| System value | One sentence: what the platform gains or protects |
| Deviations | Discrepancy-log IDs, or "none" |
| QA validation | Test names and green count; live verification yes/no with evidence; denial tests where auth is touched |
| Release state | Merged / deployed / live-verified, each with its own date |
