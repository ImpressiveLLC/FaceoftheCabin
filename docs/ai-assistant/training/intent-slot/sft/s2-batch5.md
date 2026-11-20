# S2 batch 5: authored automation proposals

Status: **UNREVIEWED** automation candidates, parent checks complete; independent PR review pending. This is an author handoff, not independent CLEAN, approved labels, a dataset freeze or authorization to train or execute.

Assignment: hub 7029373365, as supplied by the parent, continuing the automation portion of 7028714509. Base `e3af4ba94a4c61e0fb2c18581e2b6ba873f3d330`; branch `codex/s2-batch5-20261119`. Traceability: SFT data path S2, UC-17, D27. No commit or push by the author.

## Sources and scope

Sources are the [SFT authoring plan](../../../proposals/sft-data-path.md), [CLAUDE POC2 constraints](../../../../../CLAUDE.md#poc2-handoff-express_need-im-cold), [public package prompt](../system.txt), [response schema](../intent-slot.schema.json), [automation schema](../automation.schema.json) and [executable contract](../contract.py). Existing templates supply representation and baseline counts, not new utterances. Generator, vocabulary and family binder are reused for compatibility checks.

No gold, evaluation, stress, boundary, private or sealed authoring input was accessed. No model, training, network or host calls were made. All new text is authored with fictional colored devices and ordinary training room/site vocabulary, not live inventory. No actual addresses, identifiers, secret values or private text were obtained or written.

## Counts and layout

| Trigger shape | Existing rows | New templates | Added rows | Candidate total |
|---|---:|---:|---:|---:|
| Predicate, no `kind` field | 1 | 7 | 19 | 20 |
| `time_once` | 16 | 2 | 4 | 20 |
| `time_recurring` | 1 | 7 | 19 | 20 |
| `state_changes_to` | 2 | 6 | 18 | 20 |
| **Total** | **20** | **22** | **60** | **80** |

Six three-utterance predicate scenarios plus one singleton make 19 rows, as do the recurring scenarios. Two two-utterance one-time scenarios and six three-utterance transition scenarios supply the remainder.

All 158 existing templates / 425 utterances are preserved. The result is **180 templates / 485 utterances**. Eighty automation candidates are not eighty reviewed labels or measured coverage. Exposure screening or semantic review may reduce these counts. No other intent class is extended in this batch.

Predicate scenarios cover strict lower/upper illuminance, temperature and humidity comparisons, plus contact equality. Recurrence scenarios cover daily, every two days, weekly, every two weeks, monthly on a stated day, finite daily and finite hourly repetition. State transitions cover contact open/closed and presence present/absent, including two explicitly continuous dwell periods. The singleton scenarios add contact equality and finite hourly recurrence rather than extra room substitutions.

## Meaning and representation limits

- Every action and sensor target is stated with device name, room and site. No `card_target` is fabricated or needed; no context, capabilities or authorization are inferred from a live inventory. Ordinary sensor attributes and lamp/fan on/off operations describe requested fictional scenarios, not verified physical availability or resolver approval.
- All envelopes use empty slots, true confirmation, null clarification and a structured automation. Each proposal has an explicit empty conditions array and one on/off action with null attribute/value/unit and zero action delay. No need concepts, arbitrary services, safety-device disabling, unlocking or valve commands are introduced.
- Predicate comparisons preserve explicit thresholds and units; contact equality carries null unit. The predicate shape deliberately has no `kind`. State-transition wording says changes to a value; continuous dwell becomes `for_seconds`, not an action delay.
- One-time schedules use complete dates and numeric UTC offsets. Recurrences use full UTC starts, with the first occurrence explicit in every phrasing. No current date, local timezone, daylight-saving rule or relative date is inferred. Weekly patterns explicitly retain the start's weekday; the monthly start is on its stated day. Finite counts explicitly include the first occurrence.
- The response schema and contract enforce recurrence restrictions beyond the standalone automation schema. Recurrences here use valid FREQ/INTERVAL/BYMONTHDAY/COUNT forms; no COUNT/UNTIL combination or inferred stop date is introduced.
- No execution, persistence, deduplication, retry or rule-firing-frequency claim follows from a schema-valid predicate. Runtime resolution and policy checks remain separate. Saved schedules belong to the existing workflow system.
- This bounded batch does not add missing-context contrasts, card conflicts, DST cases, UNTIL boundaries, compound conditions, multiple actions, delayed action sequences or attribute-setting actions. Variants are paraphrases, not independent scenarios. Other S2 classes and need contrasts remain separate work.

## Families and validation

[s2-batch5-families.json](s2-batch5-families.json) covers exactly the 60 new generated row IDs in four conservative trigger families of 19, 4, 19 and 18. All new scenarios and variants of a trigger kind remain together. Existing families and earlier minimal-pair bindings are untouched. This batch map does not by itself reconcile cross-batch relationships: parent must do that before any later authorized split. Bind only new batch rows with this map; do not pass the whole corpus to it.

Author checks used the parent-provided offline Python environment and existing tooling, with no new test or runtime file:

- Existing generator expands and validates all 485 rows. New provenance remains `review_status: unreviewed`.
- All 60 additions pass response/request contract validation, standalone automation validation with format checking, and canonical vocabulary checks. All three schemas pass meta-validation.
- All new targets have the expected envelope, one on/off action, zero action delay and no card context. Every target name/room/site and each schedule timestamp appears in its utterance.
- Recurrence parsing and bounded inspection of at most six occurrences confirm the stated start is first; finite daily/hourly counts produce exactly five/four occurrences. No unbounded recurrence was expanded.
- Existing family binder passes exact 60-ID coverage and unique ownership in memory. No generated derivative, review state or split was written.
- All 158 prior templates compare equal to the base; IDs are unique. New utterances are unique under lowercase word-token normalization and have zero exact normalized overlap with earlier training templates. This is not a near-duplicate or cross-set screen.
- Per-kind candidate totals are exactly 20 each. `git diff --check`, local source links and recorded artifact hashes pass. No full suite or fixture-reading tests were run by the author.

Template SHA-256: `9502dbcc5284aea263ed762993f47e421c8c1875cd68a27d61726eaff45b1217`.
Family-map SHA-256: `1909a9c9c5d2e384abc95f614ae893b7cce05d89a2efb700d60af50fe1d2e68c`.

Parent owns post-freeze exposure screening, full-suite validation, publication and reciprocal review. Exposure screens, independent audit, S4 labels, S6 leakage/freeze gates and all sealed/private checks are **NOT_RUN by this author**. These checks establish compatibility and preservation, not semantic approval or independent CLEAN. All additions remain **UNREVIEWED**.

## Parent validation, 2026-11-19 21:31 CST

After author edits froze, the parent independently checked all 60 additions against dev gold (49), E3 contrast (16), CLINC stress (100), boundary candidates (92), prior training utterances (425), and quoted system-prompt fragments (61). Every group had zero normalized exact matches and zero S1 near matches. No candidate edits followed the screen. This unsealed lexical screen does not establish semantic independence or satisfy S6.

Parent independently verified generation (485 rows), preservation of the 158 base templates, exact 60-ID family coverage and in-memory binding, and read every added template and target. Existing offline suites passed: 178 package tests, 34 boundary tests and 2 grading-schema tests. Local temporary Git and loopback fixtures ran; no production host or model was contacted.

Independent PR reviews, blind labels and audit, private/sealed checks and S6 remain pending or NOT_RUN. All additions remain UNREVIEWED. The quantity target is met only at candidate level; linguistic diversity, semantic correctness and executable capability are not inferred from the counts. No training, export or deployment is authorized.
