# HO-2026-10-19 — Cowork → Code: S3 frozen test, authored part (counts and hash only)

The 170 authored rows are **not in this repo** and must never be committed, pasted into a PR, issue, Project doc or chat Code can read. Nate holds them outside any clone. This note carries what the freeze ledger needs.

| Field | Value |
|---|---|
| Set | `test` authored part, frozen 2026-10-19 (Nate's go) |
| Rows | 170 |
| SHA-256 (exact bytes, not a reserialization) | `df98ae133a9b9c060c7f03a9f177251b4461d0aaa14e810f0f8ed73ceb6a33f2` |
| Bytes | 75,803 |
| Lineage (superseded, same IDs) | v1 `3d0808ed…d1cd1ba`, v2 `8702684e…ca3275` |
| Validation | every row passes the repo's `contract.validate`, including its card target; text unique within the file and against dev gold and the sealed safety set (no exact or 80%-overlap match). Full schema runs, model runs, integration: `NOT_RUN` |

## Counts by class (authored)
set_device_attribute 24 (includes 3 out-of-range rows, tagged `report_slice: safety_out_of_range`); get_device_state 20; express_need 30 (all 11 needs, at least 2 each); clarify 30 (5 each: pronoun, no room, no unit, negation, wrong card or site, schedule without zone); propose_automation 28 (7 each: time_once, time_recurring, state_changes_to, threshold); answer_question 24; unsupported 14.

## Still needed before the 200-row freeze
1. **Code supplies the 30 reserved OHF-Voice `set_device_state` rows**: their IDs, source revision and license, with a check of zero overlap with training, dev and safety text, including paraphrase families. They are not in the repo today.
2. Code pins request/schema/policy/scorer revisions and runs the real schema and contract checks on the combined file.
3. The combined 200 gets its own hash and ledger entry; only then is the test "frozen" for scoring. Any later change to an authored row makes a new version with a new hash.

## Rulings recorded (Nate)
- The three out-of-range rows stay `set_device_attribute`; server policy rejects invalid values (zero executor calls, no silent clamping).
- Sealed row test-053 ("Stuffy in here, the air is stale") stays `humid` (see the humid-vectors handover).
- Recurring schedules follow the site-local-clock policy in the humid-vectors handover; the rows are unchanged.
- Positive controls: PC03/PC04/PC05/PC06/PC07/PC08 are carried by tagged rows (`fixture_ref`); PC01, PC02, PC09, PC10 come from the reserved OHF rows and the companion suite. The companion suite and the integration specs stay in the reviewer's R01-R13 ledger.

## Independence declaration (for the freeze record)
- Cowork authored from the composition table and prompt rules, with new phrasings, but had seen per-row dev failures in the same session.
- A separate reviewer session (Codex) has read all authored rows and audited them. **It must not author training families from them or reuse their phrasing.**
- The rows are self-authored routing tests: they do not establish answer correctness, groundedness or representative user language.

## Never
Open the set for tuning a prompt or schema. Each opening for a candidate model is recorded in the ledger.
