# S2 batch 4: authored expressed needs

Status: bounded express_need partial, **UNREVIEWED**, parent checks complete; awaiting independent PR review. Edit freeze is an author handoff, not a dataset freeze, independent CLEAN, semantic approval, training authorization or completion of S2.

Assignment: hub 7028714509, as supplied by the parent. Branch `codex/s2-batch4-20261119`; base `74fd2eb9401f9ace4794512bd8014759a5cabd58`. Traceability: SFT data path S2, UC-17, D27. No commit or push by the author.

## Sources and boundaries

Authoring uses the [SFT data path](../../../proposals/sft-data-path.md), [CLAUDE POC2 rules](../../../../../CLAUDE.md#poc2-handoff-express_need-im-cold), [current package contract](../system.txt) and [concept definitions](../concepts.json). Existing training templates were inspected for representation, counts and preservation. Schema, vocabulary, generator and family-binding code were used only for compatibility checks. No gold, evaluation, stress, boundary, private or sealed data was read; references inside the permitted public sources were not followed into those sets.

All added utterances are newly authored. They use everyday situations without live names, sites, rooms, devices, entity identifiers or private information. All targets contain only a canonical `concept`, with `requires_confirmation: true`, null clarification and null automation. No device, action, threshold, site or room is inferred. The current executable contract requires confirmation for expressed needs; this does not adopt CLAUDE's older proposed false-confirmation example.

The concept vocabulary is marked proposed. Authoring against its current schema does not approve its definitions, aliases or response mappings, expand the cold-only runtime scope described in CLAUDE, or establish any executor capability. A recognized need never selects a device by itself.

## Counts

| Need | Existing | Added | Candidate total |
|---|---:|---:|---:|
| cold | 31 | 0 | 31 |
| hot | 1 | 7 | 8 |
| humid | 6 | 2 | 8 |
| dark | 3 | 5 | 8 |
| light | 9 | 0 | 9 |
| arriving | 1 | 7 | 8 |
| leaving | 1 | 7 | 8 |
| bedtime | 3 | 5 | 8 |
| nap | 4 | 4 | 8 |
| wake_up | 4 | 4 | 8 |
| tv_time | 1 | 7 | 8 |
| **Total** | **64** | **48** | **112** |

The minimum additions needed to bring each existing need to eight are 48. Preserving the existing cold/light counts therefore yields 112 rather than exactly 100; no extra rows were added to pad the total. These are candidate counts, not the plan's reviewed-row counts. Rejection or remediation during screening may reduce them.

Nine new templates contain between two and seven utterances each, one template for each underrepresented need. All 149 existing templates / 377 utterances are preserved; the result is **158 templates / 425 utterances**. No automation candidates are added. The proposed 80-row automation target and four-trigger balance remain future work; this batch does not claim to satisfy them or the other class targets.

Humid statements concern air moisture rather than wet surfaces. Dark statements describe dim surroundings rather than requests for brightness. Arrival/departure statements describe the speaker's current or imminent transition without conditional device actions. Bedtime is explicitly overnight sleep; naps are daytime rest; wake_up is waking/getting up now, not a future wake request. Television statements express a viewing desire rather than a command to a named device. These distinctions are author intent, subject to independent labels.

The candidates vary activities and wording, but repeated paraphrases of one need are not independent scenarios. They do not cover negation, hypothetical/medical ambiguity, conflicting cards, trusted inventory, explicit room/site constraints or matched opposite-label pairs. No contextual capability or boundary coverage is inferred from this positive-only batch.

## Family handling

[s2-batch4-families.json](s2-batch4-families.json) covers exactly the 48 new generated row IDs in nine conservative need families. All new variants of each need stay together. The map is batch-only; existing families are unchanged.

The existing `grading/bind_families.py` function was exercised in memory with only the new rows. Parent must bind an authorized derivative before any later split and reconcile relationships to earlier families; this map alone does not guarantee cross-batch independence. No derivative, split or review-state change was written. Do not pass the whole corpus to this batch-only map.

## Author checks and remaining gates

Offline checks used the parent-provided interpreter and existing tooling, without model, training, network or host calls:

- Existing generator expands and validates all 425 rows; provenance remains `review_status: unreviewed`.
- Existing contract/request validators and vocabulary checks pass for all 48 additions; fields are already canonical. Response, request and automation schemas pass JSON Schema meta-validation.
- The nine canonical family groups cover all 48 new IDs exactly, with unique ownership and one need per group. Binding was checked in memory.
- All 149 base templates compare equal. Template IDs are unique. The 48 new utterances are unique under lowercase word-token normalization and have zero exact normalized overlap with earlier training templates. This is not a near-duplicate or exposure screen.
- Each canonical need has at least eight candidate utterances; the aggregate is 112. All new targets have only `concept`, true confirmation and null clarification/automation, without card context.
- `git diff --check` passes. Local source links and recorded artifact hashes were checked. No full test suite or fixture-reading test was run.

Template SHA-256: `5e2cd18c87e2219487f4b729d2f709c1399eb200aa7e4c56db7f2517256472b0`.
Family-map SHA-256: `3b351a44729dc7d6838df980b5960209a7ecced6fa2bdfead665ba36f455071e`.

Parent independently performed the checks below after author edits froze; reciprocal review remains pending. Exposure screens, independent audit, S4 labels, S6 leakage/freeze gates and all sealed/private checks are **NOT_RUN by this author**. No raw private text was obtained or written. Author checks establish format and preservation only; all additions remain **UNREVIEWED**.

## Parent validation, 2026-11-19 21:07 CST

The author froze edits before the parent read any exposure fixtures. Parent screening compared all 48 additions against dev gold (49), E3 contrast (16), CLINC stress (100), boundary candidates (92), prior training utterances (377), and quoted system-prompt fragments (61). Every group had zero normalized exact matches and zero S1 near matches. This is lexical screening of unsealed inputs, not semantic independence, a sealed-set check or S6. No candidate edits followed the screen.

Parent independently verified generation (425 rows), preservation of all 149 base templates, the 48-ID family-map coverage and in-memory binding, and the per-need counts above. Existing offline suites passed: 178 package tests, 34 boundary tests and 2 grading-schema tests. Local temporary Git and loopback fixtures ran; no production host or model was contacted. The fixture message about three failed evaluation examples is expected test output, not a failed test run.

Independent PR reviews, Track B blind labels and audit, all private/sealed screens, and S6 remain NOT_RUN or pending. The authoring session and parent checks do not supply an independent CLEAN or semantic approval. All 48 additions remain UNREVIEWED. No training, export or deployment is authorized by this batch.
