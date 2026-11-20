# S2 batch 6: explicit room/site need counterparts

Status: bounded partial READY for independent PR review; all six additions **UNREVIEWED**. Edit freeze is not a dataset freeze, independent CLEAN, label approval or training authorization.

This continues the need-contrast portion of hub assignment 7029373365 under the parent's explicit six-counterpart scope. Base `adbc9c300c8a79643781188b0293403e76fee3b9`; branch `codex/s2-batch6-20261119`. Traceability: SFT data path S2, UC-17, D27. No commit or push by the author.

## Sources and construction

Sources: [public authoring plan](../../../proposals/sft-data-path.md), [CLAUDE POC2 rules](../../../../../CLAUDE.md#poc2-handoff-express_need-im-cold), [package contract](../system.txt), current schemas/vocabulary, and existing training templates/family maps. No gold, evaluation, stress, boundary, private or sealed inputs were accessed. No model, training, network or host calls were made.

Each new utterance preserves one complete batch4 positive verbatim and appends a sentence stating location/site. No original is copied as an extra standalone row. These are deliberately related contextual counterparts, not independent novel paraphrases. The added sentence changes only the explicitly supplied context; the need concept, true confirmation and null clarification/automation remain unchanged. Absent context stays absent. No device, action, threshold, card context or opposite concept is introduced. Rooms and home/cabin are canonical fictional training context, not live inventory.

## Exact pair references

Each template below has one new utterance. Each original is utterance index 0 of the named batch4 template. Generated IDs are `synthetic-<template-id>-0` on both sides; these exact pairs define the declared expected similarity for parent screening.

| Existing template (index 0) | New template (index 0) | Appended sentence | Added slots |
|---|---|---|---|
| `train-s2b4-need-hot` | `train-s2b6-hot-room` | I am in the study. | location: study |
| `train-s2b4-need-dark` | `train-s2b6-dark-room` | I am in the den. | location: den |
| `train-s2b4-need-humid` | `train-s2b6-humid-site` | I am at home. | site: home |
| `train-s2b4-need-bedtime` | `train-s2b6-bedtime-site` | I am at the cabin. | site: cabin |
| `train-s2b4-need-nap` | `train-s2b6-nap-room-site` | I am in the guest room at home. | location: guest room; site: home |
| `train-s2b4-need-tv_time` | `train-s2b6-tv_time-room-site` | I am in the loft at the cabin. | location: loft; site: cabin |

All 180 existing templates / 485 rows are preserved. Six singleton templates produce **186 templates / 491 rows**. Expressed-need candidate count rises from 112 to 118: hot, humid, dark, bedtime, nap and tv_time each gain one; all other counts stay unchanged. This is positive context-preservation coverage, not a new clarification or negative-intent boundary set.

## Replacement family map: required handling

[s2-batch6-families.json](s2-batch6-families.json) uses the **existing batch4 canonical family keys**, including every prior member of each affected family plus its new counterpart:

| Canonical family | Existing IDs | New IDs | Replacement size |
|---|---:|---:|---:|
| train-s2b4-need-hot | 7 | 1 | 8 |
| train-s2b4-need-dark | 5 | 1 | 6 |
| train-s2b4-need-humid | 2 | 1 | 3 |
| train-s2b4-need-bedtime | 5 | 1 | 6 |
| train-s2b4-need-nap | 4 | 1 | 5 |
| train-s2b4-need-tv_time | 7 | 1 | 8 |
| **Total** | **30** | **6** | **36** |

Before any later split, **replace the six affected entries** in the batch4 map with these complete entries, retaining the three unaffected entries (arriving, leaving, wake_up). An ID-deduplicated union under the same canonical keys is equivalent. The combined map has nine families and **54 unique IDs: all 48 batch4 rows plus six new rows**.

Do not concatenate the two maps' ownership lists, create separate batch6 families for these rows, or bind only six new rows and then split them separately. Such handling would duplicate ownership or split related examples. The original batch4 map file is unchanged; this file is a replacement overlay, not a disjoint batch-only map. For standalone validation, select exactly the 36 covered rows. For the combined map, select exactly the 54 covered rows. The existing binder does not itself establish global cross-batch independence; parent retains that responsibility. No derivative or split was written here.

## Reuse and unresolved policies

Existing batch2 hypothetical pairs (15 ambiguous / 15 affirmative) and medical-meaning pairs (15 ambiguous / 15 thermal) are reused as existing coverage, with no new copies or family changes. They do not establish that all hypothetical questions or medical statements require clarification.

Cowork ruling 7030100381 approved a bounded negated-need set as clarify with empty slots, false confirmation and a meaning question. That separate piece is not included here; the public command-negation rule alone was not used to invent those labels. No negated-need row, polarity slot, opposite concept or cancellation intent is invented. Need/card conflict precedence remains unresolved and unrepresented here. No diagnosis, action selection or device capability follows from a contextualized need. Ask authoring stays deferred pending approved real-source provenance.

## Author validation and parent gates

Offline checks used the parent-provided interpreter and existing generator, contract, vocabulary and family binder:

- Generator expands and validates all 491 rows. All six new requests/targets pass, with canonical context and unreviewed provenance. Response, request and automation schemas pass meta-validation.
- All 180 existing templates compare equal to the base; all template IDs are unique. New rows have only concept plus the stated location/site, no devices/cards, and unchanged confirmation/null fields. Two room-only, two site-only and two combined contexts are verified.
- All six original sentences are exact prefixes of their declared counterparts. No original positive is duplicated as an additional row. No exact normalized full-text duplicates were found, but **six declared semantic pair relationships exist**; no zero-base-overlap or independent-novelty claim is made.
- Replacement map includes the complete affected prior families: 36 unique IDs and correct 8/6/3/6/5/8 sizes. Existing binder passes in memory for those rows. Replacing the six entries in the prior map also passes binding for 54 unique IDs, retaining every prior member and the three unaffected families.
- `git diff --check`, local links and recorded hashes pass. No full suite or fixture-reading tests were run. No model or runtime behavior was tested.

Template SHA-256: `a4911cfb4382ae64fa4611a297b47d529e2c63268b4e1edaabce8f101763a009`.
Family-map SHA-256: `9688f5ebdb53fe00594492619dd13b5cce4d73f1e9d948ca01703ee985d3a826`.

Parent screens after edit freeze. Record the six exact declared pair relationships above as expected similarity; handle them explicitly rather than asserting blanket zero overlap or exempting whole families from scrutiny. Additional matches still require review. Exposure screening, independent audit, S4 labels, S6 leakage/freeze and all private/sealed checks are **NOT_RUN by this author**. All additions remain **UNREVIEWED**, with no independent CLEAN claim.

## Parent validation, 2026-11-19 22:08 CST

After author edits froze, parent screening found zero normalized exact matches against all six unsealed exposure groups. Dev gold (49), E3 contrast (16), CLINC stress (100), boundary candidates (92) and prompt fragments (61) also had zero S1-near matches. Against the 485 existing training utterances, exactly four S1-near matches occurred: hot-room, dark-room, humid-site and bedtime-site each matched only its declared batch4 index-0 counterpart. All four share their canonical family in the replacement overlay. The nap and tv_time pairs fall below the lexical threshold but remain linked by the declared family relationship. No blanket exemption of a family or unrelated match was applied.

Parent independently verified 491 generated rows, preservation of all 180 base templates, and replacement-map binding across all 54 combined IDs with prior membership retained. Existing suites passed: 178 package tests, 34 boundary tests and 2 grading-schema tests. Local temporary Git and loopback fixtures ran; no model or production host was contacted. All added text and targets were read. Template edits stayed frozen throughout screening.

These are positive context-preservation pairs, not negative intent examples. All six additions remain UNREVIEWED; independent PR reviews, blind labeling/audit, private/sealed checks and S6 remain pending or NOT_RUN. No training, export or deployment is authorized.
