# S2 batch 2: authored clarification boundaries

Status: authored candidates, **UNREVIEWED**. Codex is the author, not an independent reviewer. No training, model call, freeze, approval or dataset split is claimed.

Assignment: Cowork hub comment 7025513615, as supplied by the parent. Base: `2f3e26d5eb02a24f5d19766b3235e4162fcacc14`; branch `codex/s2-batch2-20261119`.

## Authoring sources and scope

The semantic sources are [SFT data path, sections 3-7](../../../proposals/sft-data-path.md) and [CLAUDE.md's POC2 meaning, clarification and resolution examples](../../../../../CLAUDE.md#poc2-handoff-express_need-im-cold). Existing templates, schemas, vocabulary, generator and validation code were inspected only for representation and compatibility. No gold, sealed data, private sample, label packet, or test/evaluation fixture text was opened. No broad test suite was run by the author.

All added utterances are newly invented. Colored device names describe fictional devices; room names and `home`/`cabin` are the existing training vocabulary, not an assertion about real inventory. No real entity identifiers, addresses, credentials or people are introduced. Existing families are preserved.

## Added coverage

| Boundary | Clarify utterances | Resolved counterparts | Representation and limit |
|---|---:|---:|---|
| Ambiguous device | 15 | 15 `set_device_state` | Text explicitly names two alternatives; the counterpart chooses one. This is linguistic ambiguity, **not** a verified several-authorized-matches resolver fixture. |
| Hypothetical wording | 15 | 15 `express_need` | Incomplete hypothetical statements become present-tense thermal needs. These are not explanatory questions. |
| Medical meaning ambiguity | 15 | 15 `express_need` | The speaker explicitly leaves illness versus thermal meaning unresolved; the counterpart states thermal meaning. No diagnosis or medical advice is labeled as a device need. |
| Wrong card or site | 15 | 15 `set_device_state` | Real `card_target` request input: 9 device conflicts and 6 site conflicts. Counterparts change only the conflicting textual identity/site to match the same card. |
| Schedule without zone | 15 | 15 `propose_automation` | Real `card_target` plus a complete one-time date/time missing its offset. Each counterpart adds one explicit UTC offset; no date, target, site or zone is inferred. |
| **Total** | **75** | **75** | **150 candidates, 50 new template records.** |

The existing 75 template records / 155 utterances become 125 records / 305 utterances. Each new template has three utterances. A `train-s2b2-<type>-<n>` template and its `-pair` counterpart pair by utterance index. Every clarification has empty slots, false confirmation and null automation. Resolved mutations and needs use the current executable contract's confirmation requirement; in particular `express_need` requires true here. This does not rewrite CLAUDE's older proposed false-confirmation example or authorize execution.

The hypothetical/medical labels apply only to these ambiguous fragments/statements. Do not generalize them into a rule that a clear hypothetical question or medical explanation request must be `clarify` rather than `answer_question`.

## Capability blockers and family handling

- `request.schema.json` accepts only `text` and optional `card_target`, not an authorized candidate inventory, permissions or multiple-match resolver result. **True several-authorized-matches context remains unrepresented.** Do not count the 15 text-explicit ambiguous-device rows as closing that resolver-context gap or invent extra request/output slots.
- `card_target` is represented and retained by the generator. Its existence in the offline request contract does not prove that every runtime route forwards it. No runtime change is part of this batch.
- A single template has one target, so opposite-label minimal pairs require separate template IDs. The generator uses those IDs as separate `group` values. Do not split that raw generated output directly.
- [s2-batch2-families.json](s2-batch2-families.json) covers **only the 150 new generated row IDs**, grouped conservatively into five boundary families of 30. Each family contains both sides of every minimal pair and all room/name variants for that boundary. The existing `grading/bind_families.py` binding function accepts this map without modification; pass only the new batch rows to it. Preserve the raw generated candidates and bind a derivative during the parent's later authorized review/data preparation. No existing family assignments are changed.
- Exact normalization checks against existing training templates are not a cross-set leakage audit. Gold/sealed/private overlap and the independent S4 review/S6 freeze gates remain **NOT_RUN** here. No row is approved, promoted or training-ready.

## Author validation

Offline validation used the parent-provided Python environment (`jsonschema` 4.23.0 and `python-dateutil` 2.9.0.post0), with no model, network, host or private-data calls:

- Existing `generate_synthetic.generate`: all 305 candidates expand and validate; every emitted provenance record remains `review_status: unreviewed`.
- Existing `contract.validate` and `request_content`: all 150 new targets and requests pass, including trusted-card consistency and explicit schedule-offset validation. JSON Schema meta-validation passes for the response, request and automation schemas.
- Existing vocabulary checks: all new target fields are valid and already canonical; all card room/site values are canonical. No vocabulary edits.
- Existing `bind_families.bind`: exact coverage of all 150 new IDs, no duplicate ownership, five groups of 30, with both sides of every pair kept together. This was exercised in memory, not exported as a training split.
- Original 75 templates compare equal to the base; all template IDs are unique. Normalized new utterances are unique and have zero exact-text overlap with the existing templates. Each pair has equal utterance counts and identical card context; schedule pairs differ only by explicit offset.

Template file SHA-256 after authoring: `30601ff619d54e1b4480dbdadcf6fc59a1ccb1ba37350592ba691d88a76029b1` (LF UTF-8).

These checks establish format, compatibility and local pair integrity, not semantic label approval or independent CLEAN. Parent owns full-suite aggregate validation, PR publication and reciprocal Code/Cowork reviews. No commit or push by the author.

## Parent validation and governance

Parent validation on 2026-11-19: existing intent-slot suite 178 tests passed, boundary suite 34 passed, grading schema navigation 2 passed. Initial restricted run had six environment errors in loopback HTTP and temporary Git-repository tests; the same suite passed with those local test facilities available. The tests use existing unsealed development fixtures; none were supplied to the author as generation material. Sealed-set and private-sample checks remain NOT_RUN.

Traceability: UC-17, D27, intent SFT contract R1/R5, SFT data path S2, hub assignment 7025513615. No new backlog ID allocated. This is candidate authoring only; independent Code review and Cowork review are required for the PR, and neither repository review substitutes for S4 labels, independent audit, S6 leakage checks or training authorization. Runtime and existing features are unchanged.
