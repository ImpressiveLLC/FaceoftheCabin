# C2 tail M05: recovery heartbeat stop/restart

Assigned by Cowork on #244 comment6981947683; ownership ACK6981974954 and separate-PR sequencing6981986594. Frozen C1-S1 baseline fd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e remains unchanged. Preparation predecessor: 0b0fa651fa52a0fdc4a22d9c0e1e893e568249a7 (#502). This preparation is NOT READY until #503 integrates and the affected gates rerun.

## Bounded behavior

"How do I stop and restart the recovery heartbeat?" previously delivered general self-healing guidance. One zero-boost category recovery_heartbeat_commands, phrase `recovery heartbeat`, reuses the existing complete recovery-checks-and-notices section. Isolating the phrase from recovery_checks prevents additive scores with existing recovery-check or heartbeat-restart aliases from outranking credential guidance. No guide, matcher, service, suppression, crash classification or host change.

Only cov_M05 loses its knownGap marker. Its question, expectedCategory, expectedSection and role are unchanged. The new category supplies the same expected section under a distinct name, so category agreement is not claimed improved. The tail-plan addition records Cowork's non-blocking #502 precision note only; it does not narrow Node-RED routing.

## Preparation evidence

- Before: added exact-question regression FAIL1/1. Candidate:95focused tests PASS, zero failures/errors/skips, including120pins and all existing slice cases.
- Administrator receives the complete expected section; adult household, child, kiosk, service and null roles do not. Existing corpus test checks all six roles.
- Nine credential variants (password/token/secret crossed with bare recovery heartbeat, heartbeat restart and recovery-check overlap) retain complete credentials-and-access within6000characters.
- Real Java150-question before/after comparison: identical selected categories and full contexts for all six roles, PASS1/1. Temporary probe removed afterward; no replica used. Coverage count150/no-category16 and unmatched IDs unchanged.
- Delivery harness: only cov_M05 of103rows changes against preparation predecessor. Administrator whole delivery90/98 ->91/98 (frozen88/98); household4/5 unchanged. First agreement84/98 and expected-category-selected89/98 unchanged. Zero audience leaks/over-budget; max5143characters. Known gaps9 ->8 before M03.
- Mirrored fixtures byte-identical; existing categories and change notes retained. Guide fingerprints and frozen baseline unchanged. Final commit metadata will be rebound after integration/commit.

## Preparation fingerprints

LF-normalized SHA-256, from the real delivery reports:

| Input | Before | Candidate |
|---|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `ef85b4b6f180756c35d24bc191b60f66eb6d35266804f9c0ef66b48cd1de511d` | `866a3036f8116317299e5b417cf7eadfc399cc7855c50a725b5f32e083f5ef25` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `52793bbcc93613bc81e1bb17a18be6a961e341dc00f7917d1d8473091a9f5346` | `6ee4dc1eec56e20770361b86f667dfe591c1a39eb91e056d84c5b25c03c6bb6a` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `52793bbcc93613bc81e1bb17a18be6a961e341dc00f7917d1d8473091a9f5346` | `6ee4dc1eec56e20770361b86f667dfe591c1a39eb91e056d84c5b25c03c6bb6a` |

Spent development evidence, not held-out acceptance or answer quality. A1 NOT_MEASURED; training NOT_RUN; release_eligible false. No model, sealed content, live Ask, device, service or host action. Independent exact-head review and CI required; Cowork owns merge/deploy sequence. Local full backend suite requires unavailable Docker.
