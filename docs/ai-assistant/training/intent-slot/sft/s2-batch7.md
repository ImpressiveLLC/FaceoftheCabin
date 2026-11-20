# S2 batch 7: bounded negated-need contrasts

Status: partial READY for parent screening; four new candidates **UNREVIEWED**. The policy choice is authorized by Cowork ruling **7030100381**, as supplied by the parent. This is not independent CLEAN, independent row-label approval, a dataset freeze or training authorization.

Branch `codex/s2-batch7-20261119` starts at `468f1c9af8b7fb7b49cad3fc758bc7ca19753f0b`, the supplied #537 head. This is a dependent continuation of the room/site batch, not an edit to #537's worktree. Parent owns review and merge sequencing. Traceability: hub assignment 7029373365, SFT data path S2, UC-17, D27. No commit or push by the author.

## Sources and scope

Sources: [public authoring plan](../../../proposals/sft-data-path.md), [CLAUDE POC2 constraints](../../../../../CLAUDE.md#poc2-handoff-express_need-im-cold), [package contract](../system.txt), current schemas/vocabulary, and existing training templates/family maps. Ruling 7030100381 supplies the bounded negated-need interpretation; the existing prompt's negated-command rule alone is not claimed to settle negated observations or preferences generally. No prompt, schema or runtime rule is changed.

No gold, evaluation, stress, boundary, private or sealed authoring inputs were read. No model, training, network or host calls were made. No raw private text was obtained or written. The four candidates are the approved minimal negations of existing batch4 positives, without additional autobiographical, hypothetical, medical or command content. Existing positives are referenced, not duplicated as new rows.

## Declared pairs and questions

Indices below are zero-based. Original generated IDs are `synthetic-<original-template>-<index>`; new generated IDs are `synthetic-<new-template>-0`. These exact relationships remain declared regardless of lexical-screen results.

| Original template / index | New template | New utterance |
|---|---|---|
| `train-s2b4-need-hot` / 2 | `train-s2b7-negated-hot` | I am not uncomfortably warm in these clothes. |
| `train-s2b4-need-bedtime` / 1 | `train-s2b7-negated-bedtime` | I am not ready for my main sleep of the night. |
| `train-s2b4-need-nap` / 0 | `train-s2b7-negated-nap` | I do not want a short daytime snooze between my activities. |
| `train-s2b4-need-tv_time` / 5 | `train-s2b7-negated-tv_time` | I am not in the mood to watch a televised concert. |

All four targets use `clarify`, empty slots, false confirmation and null automation. Their approved meaning questions are respectively:

- What would you like me to understand about how you feel?
- What would you like me to understand about your readiness for sleep?
- What would you like me to understand about your wish to rest?
- What would you like me to understand about your viewing preference?

No opposite concept, affirmative need, device action, cancellation, schedule or invented polarity slot is emitted. The concert negation does not imply rejection of all television, and absence of uncomfortable warmth does not imply cold. These are statements rather than questions or negated device commands. The ruling is applied only to this approved bounded set; broader negation semantics are not asserted.

Four singleton templates add four clarification candidates: **186 templates / 491 rows become 190 templates / 495 rows**. All prior templates/rows are preserved, including batch6 context counterparts. Expressed-need counts are unchanged. Batch2 hypothetical and medical-ambiguity pairs remain existing coverage without copies or modifications. Need/card precedence remains outside this batch; Ask authoring remains deferred pending approved real-source provenance.

## Complete replacement family overlay

[s2-batch7-families.json](s2-batch7-families.json) retains the existing batch4 canonical keys and every affected batch4 **and batch6** member, then adds the new negated counterpart:

| Canonical family | Prior batch4+6 IDs | Added | Replacement size |
|---|---:|---:|---:|
| train-s2b4-need-hot | 8 | 1 | 9 |
| train-s2b4-need-bedtime | 6 | 1 | 7 |
| train-s2b4-need-nap | 5 | 1 | 6 |
| train-s2b4-need-tv_time | 8 | 1 | 9 |
| **Total** | **27** | **4** | **31** |

Apply maps in order **batch4 -> batch6 -> batch7**, replacing overlapping canonical-key entries with their complete newer lists. An ID-deduplicated union under those same keys is equivalent. The combined map has **58 unique IDs across nine families**: 48 batch4, six batch6, four batch7. All unaffected families remain intact.

Do not concatenate ownership lists or give negative rows separate split groups. This is a replacement overlay, not a disjoint batch map. For standalone binding, select exactly its 31 covered rows; for the combined map select the 58 covered rows. Preserve older map files. Parent must apply this composition before any authorized split and still review global cross-batch relationships. No derivative, split or review-state change was written here.

## Author validation and remaining gates

Offline checks used the parent-provided interpreter and existing generator, contract, vocabulary and family binder:

- Generator expands and validates all 495 rows; all four new rows retain unreviewed provenance. Request/response validation and vocabulary checks pass; response, request and automation schemas pass meta-validation.
- All 186 prior templates compare equal to the base. Template IDs are unique. New targets have exactly the approved clarification structure, without card context.
- Removing only the inserted negation reconstructs each referenced original exactly: `I am not` -> `I am`, or `I do not want` -> `I want`. No standalone positive is duplicated.
- All four new full texts are distinct, with no exact normalized full-text duplicate against prior training rows. This does **not** establish zero semantic or near overlap: four deliberate minimal-pair relationships are recorded above. A polarity-sensitive S1-near screen may not match the negations; that does not remove the family relationship.
- Replacement binding passes for 31 unique IDs with 9/7/6/9 sizes. Ordered overlay composition and binding pass for all 58 unique IDs in nine families; every previous batch4+6 member is retained.
- `git diff --check`, local source links and recorded artifact hashes pass. No full suite, exposure screen or fixture-reading test was run by the author.

Template SHA-256: `a21267edad5122fcc524cb09d5a51b9ad6037463e1c17338144f03adddcc5eb2`.
Family-map SHA-256: `2f94cbd24e11c2de3381c88ed32b63eb46425feb2b70df9a5f0412d27c38659a`.

Parent screens after edit freeze, with the four declared pair links recorded rather than a blanket near-zero claim or family-wide exemption. Parent owns full-suite validation, reciprocal review, publication and dependent sequencing. Exposure screening, independent audit, S4 labels, S6 leakage/freeze and all private/sealed checks remain **NOT_RUN by this author**. All four additions remain **UNREVIEWED**.

## Parent validation, 2026-11-19 22:46 CST

After author edits froze, all six unsealed exposure screens had zero normalized exact matches. Dev gold (49), E3 contrast (16), CLINC stress (100), boundary candidates (92) and quoted prompt fragments (61) had zero S1-near matches. Against 491 prior training utterances, exactly four S1-near matches occurred, each with its declared batch4 positive reference. All four positive/negative pairs share their canonical family through the complete ordered overlays. No unrelated match or family-wide exemption was accepted.

Parent independently checked generation (495 rows), preservation of all 186 prior templates, and complete overlay binding for 58 unique IDs across nine families. Every added utterance and target was read against ruling 7030100381. Template edits remained frozen during screening. All additions remain UNREVIEWED; this lexical screening is not independent labeling, audit, private/sealed leakage evidence or S6.

Existing offline suites passed: 178 package tests, 34 boundary tests and 2 grading-schema tests. Local temporary Git and loopback fixtures ran; no model or production host was contacted. Independent PR reviews remain required. No training, export or deployment is authorized.
