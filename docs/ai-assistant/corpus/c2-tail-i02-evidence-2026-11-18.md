# C2 tail I02: sign-in duration

Assigned by Cowork #244 comment6981947683; separate-PR sequence ACK6981986594. Immediate predecessord4296aecd812e96c99b998a251cacddabd66db33 (#504), captured separately with6/6harness tests passing. Frozen C1-S1 baselinefd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e unchanged. Integrated after #503 and M05 per Cowork6983711805 before READY.

## Bounded fix

"How long does a sign-in last before I have to sign in again?" previously supplied generic authentication guidance without the session-duration section. One zero-boost category identity_session_duration uses `a sign-in last` and the existing administrator session section. Its score3 now selects the session section ahead of generic sign-in2; credential wording also scores3 and retains full guidance in declaration order. Keeping it separate from identity_sessions avoids adding the new score to existing session-duration aliases and displacing credential guidance.

Only cov_I02 loses its knownGap marker; its exact question, expectedCategory, expectedSection and role stay frozen. No guide, matcher, session lifetime, token, auth policy or host change. Existing tests are extended rather than creating another test scaffold.

## Integrated validation

- New exact-question full-content regression FAIL1/1 before the map change. Initial candidate96focused; integrated candidate101focused; review repair102focused tests PASS, zero failures/errors/skips, including120pins and existing token-duration/managed-link/guest-link regressions.
- Administrator receives the complete source section; adult household, child, kiosk, service and null do not. All six roles checked; doc budget6000.
- Nine credential variants: password/token/secret crossed with the new phrase alone, stay-signed-in overlap and session-expiry overlap. All preserve full credentials-and-access within budget.
- Actual Java150questions before/after: selected categories and complete contexts identical for all six roles, PASS1/1. Temporary probe removed. Coverage150/no-category16, IDs unchanged.
- Only cov_I02 of103development rows changes versus immediate predecessor. Administrator whole91/98 ->92/98 (frozen88/98); household5/5 unchanged (frozen4/5). First agreement84/98 and expected-category-selected89/98 unchanged. Zero leaks/overbudget, max5143. Known gaps7 ->6; M03 and M05 are preserved.
- Mirrored fixtures identical; prior categories and notes retained. Consumed guides and frozen baseline unchanged. Final measuredRevision must be rebound after integration/commit.

## Integrated fingerprints

LF-normalized SHA-256 from the real delivery harness:

| Input | Before | Candidate |
|---|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `160ed16cf5871a61571a7e8b43ea72b22c718e437c16db08a4da1ae33721471d` | `80a1cc836a8603b0e8c4597cd90b7b1abaf008b111255d89030709dc54025df4` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `fdef6bf0c20ae03ba3e46f105a7e34c6e8670f2cf3527108eaa0f23c08b2f46f` | `f6a7e4f238e44505533d8371ce29275dc76111307d67f8c759f98e7a17564725` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `fdef6bf0c20ae03ba3e46f105a7e34c6e8670f2cf3527108eaa0f23c08b2f46f` | `f6a7e4f238e44505533d8371ce29275dc76111307d67f8c759f98e7a17564725` |

Spent development evidence, not held-out acceptance or answer quality. A1 NOT_MEASURED; training NOT_RUN; release_eligible false. No model, sealed content, live Ask, device or host action. Independent exact-head review and CI required; Cowork owns merge/deploy. Local full backend suite requires unavailable Docker.

## Independent-review repair

Cowork HOLD6984314356 found that the bare `sign-in last` phrase captured named-product durations. The phrase is narrowed to the unqualified wording `a sign-in last` in cov_I02. Ten negatives cover Home Assistant, Node-RED, Grafana, Frigate and guest-link durations, each with and without the rest of the cov_I02 question. None selects the new category or receives platform session guidance, across all six roles. The new negative regression fails on the prior head (1/1) and passes after narrowing. Credential variants now include the complete new phrase, rather than testing the removed one.

After repair102focused tests PASS, zero failures/errors/skips. A temporary real-Java comparison of the150coverage questions plus103development questions confirms every question other than the intended I02 has identical selected categories and complete contexts across all six roles against d4296aec (1/1 PASS; probe removed). The delivery report still changes only I02, with the same whole-delivery counts and no leak/budget regression. This is a bounded lexical repair, not a general product-entity resolver or proof over every possible word order. Final report metadata is rebound after committing.
