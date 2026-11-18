# C2 tail I02: sign-in duration

Assigned by Cowork #244 comment6981947683; separate-PR sequence ACK6981986594. Preparation predecessor0b0fa651fa52a0fdc4a22d9c0e1e893e568249a7 (#502). Frozen C1-S1 baselinefd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e unchanged. NOT READY: #503 and M05 must land first, then integrate and refresh evidence.

## Bounded fix

"How long does a sign-in last before I have to sign in again?" previously supplied generic authentication guidance without the session-duration section. One zero-boost category identity_session_duration uses `sign-in last` and the existing administrator session section. Its score2 ties generic sign-in2; both whole sections fit. It need not win first category. Keeping it separate from identity_sessions avoids adding the new score to existing session-duration aliases and displacing credential guidance.

Only cov_I02 loses its knownGap marker; its exact question, expectedCategory, expectedSection and role stay frozen. No guide, matcher, session lifetime, token, auth policy or host change. Existing tests are extended rather than creating another test scaffold.

## Preparation validation

- New exact-question full-content regression FAIL1/1 before the map change. Candidate96focused tests PASS, zero failures/errors/skips, including120pins and existing token-duration/managed-link/guest-link regressions.
- Administrator receives the complete source section; adult household, child, kiosk, service and null do not. All six roles checked; doc budget6000.
- Nine credential variants: password/token/secret crossed with the new phrase alone, stay-signed-in overlap and session-expiry overlap. All preserve full credentials-and-access within budget.
- Actual Java150questions before/after: selected categories and complete contexts identical for all six roles, PASS1/1. Temporary probe removed. Coverage150/no-category16, IDs unchanged.
- Only cov_I02 of103development rows changes versus preparation predecessor. Administrator whole90/98 ->91/98 (frozen88/98); household4/5 unchanged. First agreement84/98 and expected-category-selected89/98 unchanged. Zero leaks/overbudget, max5143. Known gaps9 ->8 before M03/M05.
- Mirrored fixtures identical; prior categories and notes retained. Consumed guides and frozen baseline unchanged. Final measuredRevision must be rebound after integration/commit.

## Preparation fingerprints

LF-normalized SHA-256 from the real delivery harness:

| Input | Before | Candidate |
|---|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `ef85b4b6f180756c35d24bc191b60f66eb6d35266804f9c0ef66b48cd1de511d` | `4639780f99e4213eea2339455926078ae05d31753215cfef784d662036f5ec63` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `52793bbcc93613bc81e1bb17a18be6a961e341dc00f7917d1d8473091a9f5346` | `e0ed8784b6c5cdd3e57c743498a2175fc21ab3bd778bb9fb9ec076a82965685f` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `52793bbcc93613bc81e1bb17a18be6a961e341dc00f7917d1d8473091a9f5346` | `e0ed8784b6c5cdd3e57c743498a2175fc21ab3bd778bb9fb9ec076a82965685f` |

Spent development evidence, not held-out acceptance or answer quality. A1 NOT_MEASURED; training NOT_RUN; release_eligible false. No model, sealed content, live Ask, device or host action. Independent exact-head review and CI required; Cowork owns merge/deploy. Local full backend suite requires unavailable Docker.
