# C2 tail I02: sign-in duration

Assigned by Cowork #244 comment6981947683; separate-PR sequence ACK6981986594. Immediate predecessor9b6d594968e276fa5f7268f63ab38e25e4120eb4 (#505), captured separately with6/6harness tests passing. Frozen C1-S1 baselinefd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e unchanged. Integrated after #505 per Cowork6984914961 before renewed READY.

## Bounded fix

"How long does a sign-in last before I have to sign in again?" previously supplied generic authentication guidance without the session-duration section. One zero-boost category identity_session_duration uses `a sign-in last` and the existing administrator session section. Its score3 now selects the session section ahead of generic sign-in2; credential wording also scores3 and retains full guidance in declaration order. Keeping it separate from identity_sessions avoids adding the new score to existing session-duration aliases and displacing credential guidance.

Only cov_I02 loses its knownGap marker; its exact question, expectedCategory, expectedSection and role stay frozen. No guide, matcher, session lifetime, token, auth policy or host change. Existing tests are extended rather than creating another test scaffold.

## Integrated validation

- New exact-question full-content regression FAIL1/1 before the map change. Initial candidate96focused; integrated candidate101focused; review repair102focused tests PASS, zero failures/errors/skips, including120pins and existing token-duration/managed-link/guest-link regressions.
- Administrator receives the complete source section; adult household, child, kiosk, service and null do not. All six roles checked; doc budget6000.
- Nine credential variants: password/token/secret crossed with the new phrase alone, stay-signed-in overlap and session-expiry overlap. All preserve full credentials-and-access within budget.
- Actual Java150questions before/after: selected categories and complete contexts identical for all six roles, PASS1/1. Temporary probe removed. Coverage150/no-category16, IDs unchanged.
- Only cov_I02 of103development rows changes versus immediate predecessor. Administrator whole92/98 ->93/98 (frozen88/98); household5/5 unchanged (frozen4/5). First agreement84/98 and expected-category-selected89/98 unchanged. Zero leaks/overbudget, max5143. Known gaps6 ->5; M03, M05 and I04 are preserved.
- Mirrored fixtures identical; prior categories and notes retained. Consumed guides and frozen baseline unchanged. Final measuredRevision must be rebound after integration/commit.

## Integrated fingerprints

LF-normalized SHA-256 from the real delivery harness:

| Input | Before | Candidate |
|---|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `1b8d9987b7eac4df7d71445821794783b9010047ae5792c5c867c4caab88c76b` | `28f94e699d34f3117fe2262d62bb69b730d0d2ab766e6d1541f1b74b2802e0dc` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `9ca67363d0ddda93c0ccaab502ab6051e007432afe2d2a2db45aab07dff2318a` | `3f80eb6c101fd927dc82451521694f518d50e79d8e4b93d2abdd8c2b50f231bf` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `9ca67363d0ddda93c0ccaab502ab6051e007432afe2d2a2db45aab07dff2318a` | `3f80eb6c101fd927dc82451521694f518d50e79d8e4b93d2abdd8c2b50f231bf` |

Spent development evidence, not held-out acceptance or answer quality. A1 NOT_MEASURED; training NOT_RUN; release_eligible false. No model, sealed content, live Ask, device or host action. Independent exact-head review and CI required; Cowork owns merge/deploy. Local full backend suite requires unavailable Docker.

## Independent-review repair

Cowork HOLD6984314356 found that the bare `sign-in last` phrase captured named-product durations. The phrase is narrowed to the unqualified wording `a sign-in last` in cov_I02. Ten negatives cover Home Assistant, Node-RED, Grafana, Frigate and guest-link durations, each with and without the rest of the cov_I02 question. None selects the new category or receives platform session guidance, across all six roles. The new negative regression fails on the prior head (1/1) and passes after narrowing. Credential variants now include the complete new phrase, rather than testing the removed one.

After repair102focused tests PASS, zero failures/errors/skips. A temporary real-Java comparison of the150coverage questions plus103development questions confirms every question other than the intended I02 has identical selected categories and complete contexts across all six roles against d4296aec (1/1 PASS; probe removed). The delivery report still changes only I02, with the same whole-delivery counts and no leak/budget regression. This is a bounded lexical repair, not a general product-entity resolver or proof over every possible word order. Final report metadata is rebound after committing.

## Merge after I04

Cowork's delta CLEAN on6ebeec0e and #505 merge are recorded in6984914961. The subsequent integration preserves #505's category, note, development gap removal and test class. Against immediate predecessor9b6d5949, only I02 changes among103rows: administrator92/98 ->93/98; household5/5; zero leaks/overbudget,max5143. Main category/change-note prefixes and guide hashes are unchanged. Combined105focused tests PASS, zero failures/errors/skips, including the new guest-scope tests and the existing I02 wrong-product/credential regressions. The253question six-role comparison above was performed before this integration againstd4296aec; it is not relabeled as a new run. Final exact-head harness metadata is rebound after commit.
