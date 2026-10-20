# HO-2026-10-19 — Cowork → Code: S3 safety set sealed (counts and hash only)

The 80 rows are **not in this repo** and must never be committed, pasted into a PR, issue, Project doc or chat Code can read. They are held by Nate outside any clone. This note carries only what the freeze record needs.

| Field | Value |
|---|---|
| Set | `safety` v3, sealed 2026-10-19 |
| Rows | 80 (55 `clarify`, 25 `unsupported`), all critical, all slots empty, all `requires_confirmation: false`, null automation |
| SHA-256 of the file | `90bcaace67084cb673ee36159c1d8b06493209878cefe8a8a6bb6ddbb9def310` |
| Mix | 30 clarify triggers (6 kinds x 5), 25 refusals (secrets, alarm disable, unlock, plus 5 injection), 15 injection, 10 ambiguous-need or card/site conflict rows (exact per-intent split is in the file) |
| Earlier versions (superseded, same IDs) | v1 `6fe7d4ef…085483`, v2 `d65f2e33…bb2f` |
| Validation | every row passes the repo's `contract.validate` (with the card target where one is given); model runs, executor tests: `NOT_RUN` |
| Audit | Nate reviewed through the handover rounds; a second reviewer session also read the rows (see independence) |

## Independence declaration (for the freeze record)
- Cowork authored the set from the composition table and the prompt rules, with new phrasings, but **had seen per-row dev failures** (for example gold-13, gold-15, gold-47) in the same session. It did not copy dev text.
- A separate reviewer session (Codex) has read all 80 rows. **Codex must not author training families from them or reuse their phrasing.**

## Rulings recorded (Nate, 2026-10-19)
- Valve rows 051-055 keep their current wording for now; the guarded-flow question is open (no valve capability is invented, and a "yes" never grants authority).
- Bypass rows stay `clarify` (the reviewer agreed either label is safe if the backend rejects bypass).
- `requires_confirmation` is `false` on every clarify row, matching `contract.proposal`. Two older dev rows (gold-13 and one other) still say `true`; the scorer's handling is a Code check.

## Open items that do not block the freeze
Integration, injection-surface, siren and scoring work is in the reviewer's R01-R13 ledger (returned separately); owners there are Code or Nate. UC-15 audible policy and the valve guard flow need Nate.

## For Code
Add the hash to the freeze command's ledger entry; extend `prepare_sft.py` to exclude safety families by `family_id` once the file is made available to the machine that runs the freeze (Nate places it; nothing in the repo).
