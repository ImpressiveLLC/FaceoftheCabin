# W-117 C2 slice 5 evidence: capability routes

Source baseline `e8b32bb2c5bcd4f2281a213c33456e89b955c873`. Source review and deterministic context tests only. No endpoint calls, live permission audit, model execution, training, host or device actions. A1 NOT_MEASURED; release_eligible remains false.

## Bounded scope

[Capability reference](../user-guide/capability-routes.md) maps nine administrator sections: feature prerequisites, API bases, common caller boundaries, Ask/proposals, devices/HA, events/export, camera media, family notes, workflows/alerts. This is not a complete endpoint catalogue. Existing role matrix, identity, device and workflow guides remain authoritative procedures; this slice adds source-backed route/prerequisite distinctions.

Source verification specifically distinguishes actual checks from stale comments: managed Viewers cannot POST Ask; proposal feature/administrator gates are separate from answering; model failure permits raw-facts fallback; guest roof writes require exact scope/path; camera status/configuration reads are not playable-media proof; generic signed-in mutation access is not administrator-only where code lacks that check. Family note author attribution is request-body data, not principal binding.

## Tests and checks

| Check | Result |
|---|---|
| Focused Maven suite | 61 passed, zero failures/errors/skips: capability5, matcher9, builder22, guide-alias3, routing-coverage13, document-source9 |
| Final wording and negative-case update | All five capability tests rerun and passed |
| Eighteen authored positive/failure questions | Every requested section delivered whole in actual Java, no truncation, document context at most6000characters |
| Audience | Every case denies the new sections to adult household, child, kiosk, service and null roles |
| Mixed caller/helpdesk question | Both complete sections fit the existing budget |
| Credential priority | Four credential-bearing route/API questions retain complete credentials-and-access |
| Fixture integrity | Docs/backend bytes identical; all pre-existing JSON fields and category/change-note prefixes preserved against baseline; unique IDs |
| Source links | All41 destinations and linked Markdown anchors verified |
| Size/privacy | Nine sections, largest raw section2151characters; no literal IPv4 or instance hostname/credential value introduced |
| Full integration / live | NOT_RUN; local Docker unavailable. Remote exact-head CI remains required |

[Acceptance questions](capability-routes-cases-2026-11-18.json) are authored regression inputs outside the reviewed-document allowlist, not gold or sealed material. These checks prove deterministic context delivery, not semantic coverage of all possible phrasings or answer quality.

The API-base positive asks whether a signed-in request can redirect. The variant explicitly asking about redirecting credentials intentionally remains on credential_redaction and is tested as a negative; this slice does not boost a general API category above credential guidance or claim the variant delivers both sections.

## route.py before/after

Explicit UTF-8 run against baseline and changed maps:

- New18: no-category7before,0after. Seventeen select the intended capability category first; export permissions with a guest link ties access_sharing, whose earlier declaration wins in the replica. Actual Java still delivers the complete requested event/export section.
- Existing150: no-category16before/after; entire output unchanged.

The script uses older plural matching and first-winner behavior. Java additionally handles endings and whole-section ties; actual delivered-context tests are the acceptance evidence. All new categories use zero boost and bounded two-word phrases; no generic credential keywords were added.

## Queue and release gates

Cowork assigned this work in #244 comment6976102196; ACK6976121795. Own branch from the stated baseline. Queue: #487 then #490 then #491, with this slice proposed after491. Shared fixture integration requires preserving all categories and notes, rerunning combined context/role/credential/budget checks, and renewed full-SHA READY. This head does not claim to include still-pending predecessor slices.

Cowork independently reviews and owns the pinned merge/deployment sequence. Author checks are not independent CLEAN. Resource/test changes trigger backend CI/deployment; this is not docs-only/no-deploy. A successful deploy or live health check does not authorize training or establish Ask answer quality.
