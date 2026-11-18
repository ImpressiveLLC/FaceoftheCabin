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
