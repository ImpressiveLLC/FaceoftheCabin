# W-117 C2 slice 4 evidence: stack map

Source baseline: `e8b32bb2c5bcd4f2281a213c33456e89b955c873`. This is source review plus deterministic retrieval testing only. Runtime topology, firewall/tunnel enforcement, model execution, training and clean-host installation are NOT_VERIFIED or NOT_RUN. A1 NOT_MEASURED; release_eligible remains false.

## Scope

[Stack map](../user-guide/stack-map.md) contains seven administrator sections: stack ownership/networks; application ports; device-stack ports; browser/API proxy; telemetry/camera connections; model connections; Home collector. Each category maps exactly one section. Existing configuration, installation and recovery procedures are referenced rather than replaced. No deployment, API or matcher implementation changes.

Source details preserved:

- Base plus M920q overlay is distinct from the production device stack. Profile-disabled declarations are not active replacements.
- Host mappings, unpublished internal listeners and Home Assistant host networking are distinguished.
- Tailscale-only comments are not firewall evidence. External cloudflared routes are not inferred from Compose.
- Ollama's actual `default` network declaration differs from an older `cabin_default` comment; the discrepancy stays explicit.
- HOME_HA_DEPLOYED falls back to HOME_HUB_DEPLOYED and then false. It describes expected Home HA availability; it does not disable the collector or deploy HA.
- A container health check, a broker connection, recent observations and working camera video prove different things.

## Validation

| Check | Result |
|---|---|
| Focused Maven suite | 61 passed, zero failures/errors/skips: stack-map5, matcher9, builder22, guide-alias3, routing-coverage13, document-source9 |
| Final guide wording change | All five stack-map tests rerun and passed |
| Fourteen authored positive/failure questions | Actual Java delivers the complete requested section for every case, without truncation, within 6000 document characters |
| Role boundaries | Every case denies stack-map sections to adult household, child, kiosk, service and null roles |
| Mixed application/support port question | Both complete sections delivered within budget |
| Credential precedence | Backend-port password, Home-collector secret and model-connection token questions retain the complete credentials-and-access section |
| Fixture copies | Byte-identical; pre-existing JSON fields preserved against main; category IDs unique |
| Source links | All 34 destinations exist; linked Markdown headings verified |
| Size and privacy | Seven sections; largest raw section 1971 characters; no literal IPv4 or instance hostnames/credential values added |
| Runtime / full Docker integration | NOT_RUN for this documentation/resource slice; local Docker unavailable; remote exact-head CI required |

The new questions are [authored regression cases](stack-map-cases-2026-11-18.json), outside the reviewed-document allowlist. They are neither gold nor sealed evaluation material. Tests inspect delivered section text, not merely category names. They establish deterministic context delivery, not model answer quality.

## Existing route.py before/after

Executed with explicit UTF-8 against baseline and changed fixtures:

- New14: no-category9 before,0 after. Twelve report the intended stack category first; two tie with earlier categories (camera_frames and config_env_addresses). Actual Java delivers the requested stack sections in both ties.
- Existing150: no-category16 before and after; entire output unchanged.

The replica script uses older plural matching and only the first winner. Current Java supports additional endings and whole-section union for ties; the Java tests are the authority. Most stack categories have zero boost. Home collector has boost1 so it ties the named Home HA configuration flag without outranking single-word credential guidance; tests cover both boundaries.

## Integration and review

Proposed queue: #487, #490, then this slice, subject to Cowork's recorded order. The fixture is shared: the later author merges fetched main, preserves all independently authored categories and change notes, runs affected context/role/credential/budget tests, then posts a new full-head READY. This source-baseline branch does not claim to include the still-open preceding slices.

Cowork owns independent review and merge/deployment sequencing. Author checks are not an independent CLEAN. Resource/test paths trigger backend CI/deployment; this is not represented as a docs-only no-deploy change. Live health verification after merge does not establish Ask answer quality or authorize model training.

## Ordered integration after #490

Integrated main `f7459bd68e91c8b1e26fc206fec90899c0ecbefd` after Cowork merged #490. The two fixture conflicts were resolved by preserving all current-main JSON fields/categories/change notes and appending only this slice's seven categories and one note. Mirrored bytes remain identical and IDs unique. No source-reference or runtime change in this integration.

Combined recovery, identity, stack, matcher, builder, guide-alias, routing and document-source suite: 72 passed, zero failures/errors/skips. The original20 recovery cases and identity credential regressions are included. Existing150 replica output is byte-for-byte identical against this integrated main baseline (16no-category). Fresh exact-head CI and independent Cowork review are required; prior author checks are not CLEAN.

## Slice 8: everyday stack phrasing follow-up

Assigned by Cowork on #244 comment6977777870; baseline `7382d38c8bffed5fef938b4239ce062e2768fd2f`. Adds exactly three phrases to existing zero-boost categories: `backend use`, `home assistant port`, `mqtt to kafka`. No new category, source section, matcher or execution behavior. Existing phrase lists and all unrelated fixture fields remain unchanged.

The three assigned questions now deliver their entire existing section: which port the backend uses, the Home Assistant port, and telemetry from MQTT to Kafka. The authored stack suite expands from14 to17 cases. Twelve credential-bearing negatives (three existing plus nine password/token/secret variants) retain complete credentials-and-access; the three-word phrases may tie credential guidance but do not displace it or exceed the6000-character budget. These are bounded lexical aliases, not general semantic understanding; untested phrasings are not claimed covered.

72combined tests passed, zero failures/errors/skips: stack5, identity6, recovery5, matcher9, builder22, guide-alias3, routing13, source9. Existing150 replica output unchanged (16no-category). Mechanical comparison confirms only the three aliases and appended change note changed in fixtures; mirrors byte-identical. The guide's original baseline date is verified by git author and committer timestamps: e8b32bb2c5bcd4f2281a213c33456e89b955c873,2026-11-18T03:34:17-06:00. No guide date correction required.

Pending #492/#493 are not in this baseline. Ordered integration and renewed exact-head review/CI are required before merge. No model run, training, live Ask request or host change. A1 NOT_MEASURED; release_eligible remains false. Author checks are not independent CLEAN.

## Slice 8 integration and assigned capability additions

Integrated main `b88d416dca7194bc7eb4ac39bee79df02225b804` after #492 merged. Cowork comment6978245948 also assigned two bounded capability gaps to this slice: "What does the propose route need?" and "Who can call POST /api/ha/services?". Added `propose route` and `call post /api/ha/services` to the existing zero-boost categories. The latter phrase scores3, above generic `who can`2, while credential negatives still retain complete credentials-and-access. No matcher, role, API or execution change.

Current delta against integrated main is exactly five appended aliases and one change note in mirrored fixtures. Existing fields/category contents otherwise preserved. Stack17 and capability20 authored cases deliver whole sections, including all five newly assigned questions. Stack12 and capability10 credential negatives retain complete guidance. Combined suite77passed, zero failures/errors/skips;150existing replica outputs unchanged,16no-category. The small evidence spacing typo assigned by Cowork is corrected.

Updated queue per Cowork6978218250: #492 (merged), this slice #495, #493, then docs evidence #494. Fresh exact-head READY, CI and independent Cowork review required. Earlier72-test results describe the pre-integration stack-only head;77is the current combined result. No live/model/training claim.
