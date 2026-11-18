# C2 tail M05: recovery heartbeat stop/restart

Assigned by Cowork on #244 comment6981947683; ownership ACK6981974954 and separate-PR sequencing6981986594. Frozen C1-S1 baseline fd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e remains unchanged. Immediate predecessor: 0aea2f56b35d8a68a9ce4d6c182a8bb5028742df (#503), captured in an isolated checkout with6/6harness tests passing. Integrated before READY per Cowork6983140307.

## Bounded behavior

"How do I stop and restart the recovery heartbeat?" previously delivered general self-healing guidance. One zero-boost category recovery_heartbeat_commands, phrase `recovery heartbeat`, reuses the existing complete recovery-checks-and-notices section. Isolating the phrase from recovery_checks prevents additive scores with existing recovery-check or heartbeat-restart aliases from outranking credential guidance. No guide, matcher, service, suppression, crash classification or host change.

Only cov_M05 loses its knownGap marker. Its question, expectedCategory, expectedSection and role are unchanged. The new category supplies the same expected section under a distinct name, so category agreement is not claimed improved. The tail-plan addition records Cowork's non-blocking #502 precision note only; it does not narrow Node-RED routing.

## Integrated evidence

- Before: added exact-question regression FAIL1/1. Initial candidate95focused; integrated candidate99focused tests PASS, zero failures/errors/skips, including120pins and all existing slice cases.
- Administrator receives the complete expected section; adult household, child, kiosk, service and null roles do not. Existing corpus test checks all six roles.
- Nine credential variants (password/token/secret crossed with bare recovery heartbeat, heartbeat restart and recovery-check overlap) retain complete credentials-and-access within6000characters.
- Real Java150-question before/after comparison: identical selected categories and full contexts for all six roles, PASS1/1. Temporary probe removed afterward; no replica used. Coverage count150/no-category16 and unmatched IDs unchanged.
- Delivery harness: only cov_M05 of103rows changes against immediate predecessor. Administrator whole delivery90/98 ->91/98 (frozen88/98); household5/5 unchanged (frozen4/5). First agreement84/98 and expected-category-selected89/98 unchanged. Zero audience leaks/over-budget; max5143characters. Known gaps8 ->7; M03 is preserved.
- Mirrored fixtures byte-identical; existing categories and change notes retained. Guide fingerprints and frozen baseline unchanged. Final commit metadata will be rebound after integration/commit.

## Integrated fingerprints

LF-normalized SHA-256, from the real delivery reports:

| Input | Before | Candidate |
|---|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `03e93bfbd089111d7af141c8ba23c1872244872e1dcdef21c37e662539c10bee` | `160ed16cf5871a61571a7e8b43ea72b22c718e437c16db08a4da1ae33721471d` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `06d1f9bc995a9d51a9fdf8672ecd9bba509cef72a3860759fb643ecb65f65c8b` | `fdef6bf0c20ae03ba3e46f105a7e34c6e8670f2cf3527108eaa0f23c08b2f46f` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `06d1f9bc995a9d51a9fdf8672ecd9bba509cef72a3860759fb643ecb65f65c8b` | `fdef6bf0c20ae03ba3e46f105a7e34c6e8670f2cf3527108eaa0f23c08b2f46f` |

Spent development evidence, not held-out acceptance or answer quality. A1 NOT_MEASURED; training NOT_RUN; release_eligible false. No model, sealed content, live Ask, device, service or host action. Independent exact-head review and CI required; Cowork owns merge/deploy sequence. Local full backend suite requires unavailable Docker.
