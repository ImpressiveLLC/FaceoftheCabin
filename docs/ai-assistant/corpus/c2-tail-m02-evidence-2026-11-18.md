# C2 tail M02: Node-RED editor login wording

Assigned by Cowork on #244 comment6980766894, confirmed6980961512; ownership ACK6980801389. Frozen C1-S1 baseline: fd1f8676fe55dd1ee9c1efaed1e1784abf96ca6e (runtime/content82f836f). Immediate predecessor: c05e9a009a1000952297f6bb2eecd9a2a9b8ef66, captured in its own detached checkout before comparison. The frozen baseline record is unchanged.

## Bounded fix

The exact reported question "I can't log in to the Node-RED editor. What now?" previously had no category. A single zero-boost category, recovery_nodered_editor, matches node-red editor and reuses the complete existing node-red-login-recovery section. No guide, matcher, authentication, credential or host change. Existing recovery tests are extended, not duplicated into a new test scaffold.

The separate mapping prevents additive keyword scores from hiding credential guidance. Adding the editor phrase to recovery_nodered would sum it with existing login or password-rotation phrases in mixed questions. Isolating the score2 editor mapping preserves the existing score3 credential route and old rotation ties. Nine password/token/secret questions test simple editor wording, login-plus-editor wording and rotation-plus-editor wording. All retain full credentials-and-access within6000characters.

Only cov_M02 loses its knownGap marker; question, expected category, expected section and role stay frozen. The selected category name differs from the frozen expected category, but supplies the same full section. First/selected agreement is therefore not claimed improved.

## Measured delta

| Measure | Frozen baseline | Immediate predecessor after C07 | Candidate after M02 |
|---|---:|---:|---:|
| Administrator whole delivery |88/98|89/98|90/98|
| Household whole delivery |4/5|4/5|4/5|
| Administrator no-category |2|2|1|
| Administrator first agreement |84/98|84/98|84/98|
| Administrator expected category selected |89/98|89/98|89/98|
| Known gaps |11|10|9|
| Audience leaks / over-budget |0/0|0/0|0/0|

Only cov_M02 changes versus the immediate predecessor. C07 and all other102 row results are identical. Maximum context remains5143characters. Coverage-map report remains150questions/16no-category with identical unmatched IDs.

## Validation and limits

- Before routing change: new exact-question recovery regression FAIL1/1. Initial isolated fix:89focused tests PASS. After integrating C07:93focused tests PASS, zero failures/errors/skips, including120routing pins, C07 tests and real delivery harness.
- Recovery corpus now21 authored cases; all previous20 preserved. Administrator gets the exact full requested section. Adult household, child, kiosk, service and null do not receive its administrator-only section; all six roles explicitly covered by the existing corpus-driven test.
- Nine added credential-overlap variants pass, plus the existing credential and rotation checks. No operational action was run.
- Actual Java150-question before/after probe on the initial07b7e21 branch: categories and complete contexts identical across all six roles, PASS1/1. Integrated harness retains the same150count/IDs and120pins. The retired replica is not used.
- Main fixture fields/category/change-note prefixes preserved; only the new mapping and note appended. Mirrors byte-identical. Development JSON differs from predecessor only by clearing cov_M02. Source guides and frozen baseline evidence unchanged.

## Input fingerprints

LF-normalized SHA-256 from the immediate-predecessor and candidate reports. Final measuredRevision is checked again after committing.

| Input | Predecessor | Candidate |
|---|---|---|
| `docs/ai-assistant/corpus/c1-dev-retrieval-set-2026-11-18.json` | `291aef2e5a0174012152a2ad1f44b21762c5565e9075dad325f443c081827ea2` | `ef85b4b6f180756c35d24bc191b60f66eb6d35266804f9c0ef66b48cd1de511d` |
| `docs/ai-assistant/rag/context-fixtures-r2.json` | `74c585112435a5651afa012a7f672712408cfb1401b5b7c5ab7eb1b5848e7a2b` | `f3328404bd0be3f42b55c5546e1110e59b28187ce0db7778bb8742054a837186` |
| `cabin-orchestration-platform/backend/src/main/resources/rag/context-fixtures-r2.json` | `74c585112435a5651afa012a7f672712408cfb1401b5b7c5ab7eb1b5848e7a2b` | `f3328404bd0be3f42b55c5546e1110e59b28187ce0db7778bb8742054a837186` |

All consumed-guide fingerprints match. These are spent development results, not answer-quality or acceptance evidence. A1 NOT_MEASURED, training NOT_RUN, release_eligible false. No model, sealed material, live Ask, device or host action. Independent Cowork exact-head review, CI and named merge/deployment owner remain required; author checks are not CLEAN.
