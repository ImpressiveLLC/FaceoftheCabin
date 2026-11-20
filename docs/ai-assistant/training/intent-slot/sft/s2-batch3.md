# S2 batch 3: authored unsupported boundaries

Status: bounded partial author handoff, **UNREVIEWED**. Edits are ready for parent review; semantic labels are not approved. This is not independent CLEAN, a dataset freeze, training readiness or completion of S2.

Assignment: Cowork 7027949404, as supplied by the parent, permits partial READY for the unsupported class. Base: `2a6428507248211123f3db3a6ff13e9bcd23e486`; branch `codex/s2-batch3-20261119`. Traceability: UC-17, D27, SFT data path S2. No new backlog ID or runtime behavior.

## Sources and scope

Authoring uses the [SFT data path](../../../proposals/sft-data-path.md), [CLAUDE POC2 rules](../../../../../CLAUDE.md#poc2-handoff-express_need-im-cold), and [current package contract](../system.txt). Existing templates were inspected for representation and preservation; schemas, vocabulary, generator and family binder were inspected for compatibility. No gold, evaluation fixture, sealed set, private sample or label packet was accessed. No model, training, network or host call was made.

All new utterances are original candidates. Named networks, accounts, integrations and devices are fictional. Standard room names and `home`/`cabin` are training vocabulary, not live inventory. Requests for secret disclosure contain no secret values, addresses, entity IDs or executable service calls.

The current contract routes secret/credential requests, indefinite life-safety disabling and lock unlocking to `unsupported`. Requests to ignore policy and commands to open a valve route to `clarify`; neither is added to the unsupported count. The older plan's safety-set category list groups bypass and valve opening under unsupported, so that list is not used as the label authority here.

## Added coverage

| Boundary | Templates | Unsupported utterances | Benign counterparts |
|---|---:|---:|---:|
| Secret disclosure: Wi-Fi password, broker password, integration token, backup decryption key, lock access code, connector client secret | 6 | 18 | 0 |
| Indefinite disabling: smoke alarms during cooking, unused-room retirement, nuisance warnings; leak alarms for splashes, replacement detector, abandonment | 6 | 18 | 0 |
| Unlocking: parcel handoff, tools, missing key, installer, furniture move, notebook retrieval | 6 + 6 paired | 18 | 18 `get_device_state` |
| **Total** | **24** | **54** | **18** |

Each template has three utterances. The 125 existing templates / 305 utterances become **149 templates / 377 utterances**. Unsupported-labeled rows increase from 6 to **60**; this is a count of candidates, not 60 reviewed or approved labels. The other class targets remain pending future batches. Eighteen unsupported scenarios with three phrasings each do not establish 54 independent scenarios or broad adversarial coverage.

Every new unsupported target has empty slots, false confirmation and null clarification/automation. The 18 counterparts carry only the named device, stated room and stated site, with false confirmation and null clarification/automation. They request the current lock state without asserting its value, availability, authorization or a new read capability. The resolver and policy boundary still applies.

The six `train-s2b3-unlock-N` templates pair with their `-pair` templates by utterance index. Only `unlock` changes to `report the current state of`; all target wording and scenario context stays identical. These pairs distinguish an unsupported mutation from the contract's one-device state query, not an authorized unlock from an unauthorized one. No card or inventory context is fabricated.

Secret-presence/status pairs are omitted: the request contract does not establish a credential-status device state, and teaching such a mapping would invent a capability. Life-safety disabling pairs are also omitted rather than assuming that a temporary disable is permitted or an alarm-enable operation is supported. No additional counterpart quota is claimed.

Existing families are untouched. Four inherited unsupported rows concern subzero thermostat values: CLAUDE explicitly says below zero is unsupported, while the package prompt's temperature wording is less specific. This pre-existing source discrepancy is not resolved by this batch, and the aggregate count must not be mistaken for a fresh approval of those labels.

## Family handling

[s2-batch3-families.json](s2-batch3-families.json) covers exactly the 72 new generated IDs in three conservative boundary families: secrets 18, life-safety 18, unlocking and lock-state counterparts 36. All scenarios and paraphrases within each boundary are kept together, including both sides of each pair. Template IDs alone are not the final split groups.

The existing `grading/bind_families.py` function was exercised in memory with only these 72 rows and this map. The parent must bind an authorized derivative before any later split; do not split the raw generator groups directly or pass the entire corpus to this batch-only map. No existing family assignment, review state or split was changed. Cross-batch family relationships remain subject to the parent's later review/leakage controls.

## Author validation and remaining gates

Offline validation used the parent-provided tooling interpreter with `jsonschema` and `python-dateutil`. No new validation tool or test file was added.

- Existing generator: all 377 rows expand and validate; generated provenance remains `review_status: unreviewed`.
- Existing contract/request validation and vocabulary checks: all 72 new rows pass, with canonical fields and no normalization changes. Response, request and automation schemas pass JSON Schema meta-validation.
- Existing family binder: exact coverage of 72 IDs, unique ownership, family sizes 18/18/36; all pairs remain together. No derivative or training split was written.
- All 125 original templates compare equal to the base. Template IDs are unique. New utterances are unique under lowercase word-token normalization and have zero exact normalized overlap with the prior training templates. This is not a near-duplicate or cross-set leakage screen.
- All 18 pairs differ only by the documented action/query phrase. New intent counts are exactly 54 unsupported and 18 get-device-state.
- `git diff --check` passes; local source links resolve. No full test suite was run by the author.

Template SHA-256: `872aef975508750d4aa977804a71475ea03f9364e7ad6fb1eb5446d9994d62ee`.
Family-map SHA-256: `5a3b10034237daa7258cd112b2ea87cc7e4b48d0c94b73758b20feab005e37ad`.

Parent owns all six unsealed exposure lexical screens after edit freeze, the full existing suite, publication and reciprocal Code/Cowork reviews. Those screens are **NOT_RUN by this author**; the local exact-template comparison above is not a substitute. Sealed/private checks, S4 independent semantic labels and S6 dataset freeze/leakage gates remain **NOT_RUN**. No raw private text was obtained or written. No commit or push by the author.

## Parent verification

On 2026-11-19 the parent screened all72 new candidates against six existing unsealed exposure groups: dev gold49, E3 16, CLINC stress100, boundary92, prior template utterances305 and quoted prompt fragments/examples61. Each comparison produced zero normalized-exact matches and zero S1-near matches. This supersedes the author-stage NOT_RUN for these specific lexical screens only; no semantic or sealed/private independence is claimed. No source fixture text was supplied to the author. Generator377, uniqueness and family-map coverage passed. Existing intent-slot suite178, boundary suite34 and grading-schema tests2 passed. No private S5 data was accessed.

Release gate: Code reciprocal exact-head review and Cowork independent review are required. Author validation is not CLEAN; S4 labels, independent audit, freeze and training authorization remain separate. This partial batch addresses candidate quantity for unsupported only; expressed needs, automation and factual-answer coverage remain unfulfilled.
